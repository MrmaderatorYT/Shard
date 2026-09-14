package com.ccs.shard.core.cloud.drive;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.ccs.shard.core.cloud.CloudBackend;
import com.ccs.shard.core.cloud.CloudEntry;
import com.ccs.shard.core.cloud.CloudException;
import com.ccs.shard.core.cloud.CloudHttpAccess;
import com.ccs.shard.core.cloud.CloudProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Google Drive over the REST v3 API.
 *
 * <p>Drive addresses everything by opaque file id, including folders, so this
 * class carries the whole id-mapping burden that Dropbox (path-addressed) will
 * not need: a per-session cache of directory ids, plus a lookup-or-create for
 * each vault subfolder. File ids for notes themselves are the sync core's
 * business and live in {@code CloudIndex}.
 *
 * <p>With the {@code drive.file} scope Shard can only see what it created, so
 * the vault folder is created under {@code My Drive} on first connect and
 * found by name afterwards.
 */
public final class DriveBackend implements CloudBackend {

    private static final String FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    /** Ask only for what we store; Drive returns a lot by default. */
    private static final String FIELDS = "id,name,headRevisionId,trashed";

    private final Context context;
    private final DriveAuth auth;
    private final String rootFolderName;

    /** Vault-relative directory path (\"\" for the root) to Drive folder id. */
    private final Map<String, String> folderIds = new HashMap<>();

    public DriveBackend(Context context, DriveAuth auth, String rootFolderName) {
        this.context = context.getApplicationContext();
        this.auth = auth;
        this.rootFolderName = TextUtils.isEmpty(rootFolderName) ? "Shard" : rootFolderName;
    }

    @Override public CloudProvider provider() {
        return CloudProvider.GOOGLE_DRIVE;
    }

    @Override public String accountLabel() {
        return auth.accountName();
    }

    @Override public void connect() throws CloudException {
        auth.token();
        folderIds.clear();
        folderIds.put("", ensureFolder(rootFolderName, null));
    }

    @Override public CloudEntry upload(String relPath, File local, CloudEntry previous)
            throws CloudException {
        if (local == null || !local.isFile()) {
            throw new CloudException(CloudException.Reason.CONFIGURATION);
        }
        // Snapshot the stamps before the upload: if the file changes while it
        // is in flight, recording the post-upload mtime would make the index
        // claim the newer bytes are already remote.
        long modified = local.lastModified();
        long size = local.length();

        String existingId = previous != null ? previous.remoteId() : null;
        if (!TextUtils.isEmpty(existingId)) {
            try {
                return new CloudEntry(provider(), existingId,
                        updateContent(existingId, local, relPath), modified, size);
            } catch (CloudException e) {
                if (e.reason() != CloudException.Reason.NOT_FOUND) throw e;
                // Somebody deleted it in Drive. Fall through and create a new
                // one rather than failing forever on a stale id.
            }
        }
        String parentId = folderFor(parentOf(relPath));
        String created = createWithContent(parentId, nameOf(relPath), local, relPath);
        return new CloudEntry(provider(), created, revisionOf(created), modified, size);
    }

    @Override public void remove(String relPath, CloudEntry previous) throws CloudException {
        if (previous == null || !previous.hasRemoteId()) return;
        try {
            CloudHttpAccess.request("DELETE", FILES + "/" + enc(previous.remoteId()),
                    auth, null, null, null);
        } catch (CloudException e) {
            // Already gone is the desired state, not a failure.
            if (e.reason() != CloudException.Reason.NOT_FOUND) throw e;
        }
    }

    @Override public void close() {
        folderIds.clear();
        auth.forget();
    }

    // ------------------------------------------------------------------ files

    private String createWithContent(String parentId, String name, File local, String relPath)
            throws CloudException {
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("name", name);
            metadata.put("parents", new JSONArray().put(parentId));
        } catch (Throwable t) {
            throw new CloudException(CloudException.Reason.REMOTE, t);
        }
        // Resumable uploads would be better for large attachments, but they
        // cost an extra round trip per file and notes are kilobytes; a plain
        // multipart upload keeps the common case to one request.
        return CloudHttpAccess.multipartUpload(
                UPLOAD + "?uploadType=multipart&fields=" + enc(FIELDS),
                auth, metadata.toString(), local, mimeOf(relPath));
    }

    private String updateContent(String fileId, File local, String relPath) throws CloudException {
        JSONObject response = CloudHttpAccess.upload(
                "PATCH",
                UPLOAD + "/" + enc(fileId) + "?uploadType=media&fields=" + enc(FIELDS),
                auth, null, local, mimeOf(relPath)).json();
        // A trashed file still answers PATCH, so the bytes would land somewhere
        // the user cannot see. Treat it as missing and let the caller recreate.
        if (response.optBoolean("trashed", false)) {
            throw new CloudException(CloudException.Reason.NOT_FOUND);
        }
        return response.optString("headRevisionId", "");
    }

    private String revisionOf(String fileId) {
        try {
            return CloudHttpAccess.request("GET",
                    FILES + "/" + enc(fileId) + "?fields=" + enc(FIELDS),
                    auth, null, null, null).json().optString("headRevisionId", "");
        } catch (Throwable t) {
            // The revision is only a hint for a future two-way sync; losing it
            // is not worth failing an otherwise successful upload.
            return "";
        }
    }

    // ---------------------------------------------------------------- folders

    /** Resolves a vault-relative directory path to a Drive folder id, creating as needed. */
    private String folderFor(String dirPath) throws CloudException {
        String cached = folderIds.get(dirPath);
        if (cached != null) return cached;
        if (dirPath.isEmpty()) {
            String root = ensureFolder(rootFolderName, null);
            folderIds.put("", root);
            return root;
        }
        String parent = folderFor(parentOf(dirPath));
        String id = ensureFolder(nameOf(dirPath), parent);
        folderIds.put(dirPath, id);
        return id;
    }

    private String ensureFolder(String name, String parentId) throws CloudException {
        String found = findChild(name, parentId, true);
        if (found != null) return found;
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("name", name);
            metadata.put("mimeType", FOLDER_MIME);
            metadata.put("parents", new JSONArray().put(parentId == null ? "root" : parentId));
        } catch (Throwable t) {
            throw new CloudException(CloudException.Reason.REMOTE, t);
        }
        return CloudHttpAccess.request("POST", FILES + "?fields=" + enc(FIELDS), auth,
                CloudHttpAccess.json(), metadata.toString().getBytes(charset()), "application/json")
                .json().optString("id", "");
    }

    private String findChild(String name, String parentId, boolean folder) throws CloudException {
        StringBuilder query = new StringBuilder();
        query.append("name = '").append(escapeQuery(name)).append("'");
        query.append(" and '").append(escapeQuery(parentId == null ? "root" : parentId))
                .append("' in parents");
        query.append(" and trashed = false");
        if (folder) query.append(" and mimeType = '").append(FOLDER_MIME).append("'");

        JSONObject response = CloudHttpAccess.request("GET",
                FILES + "?q=" + enc(query.toString())
                        + "&spaces=drive&pageSize=1&fields=" + enc("files(id,name)"),
                auth, null, null, null).json();
        JSONArray files = response.optJSONArray("files");
        if (files == null || files.length() == 0) return null;
        JSONObject first = files.optJSONObject(0);
        if (first == null) return null;
        String id = first.optString("id", "");
        return id.isEmpty() ? null : id;
    }

    /**
     * Drive's query language is single-quoted with backslash escapes. Note
     * names come from the vault, so a title with an apostrophe would otherwise
     * produce a malformed query - or, worse, a query that matches the wrong
     * folder.
     */
    private static String escapeQuery(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    // ------------------------------------------------------------------ paths

    private static String parentOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash <= 0 ? "" : relPath.substring(0, slash);
    }

    private static String nameOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash < 0 ? relPath : relPath.substring(slash + 1);
    }

    private String mimeOf(String relPath) {
        String name = nameOf(relPath);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase();
            if (ext.equals("md") || ext.equals("tex") || ext.equals("canvas")) {
                // Keep Markdown as text/markdown rather than letting Drive
                // guess: it otherwise offers to convert notes to Google Docs.
                return ext.equals("md") ? "text/markdown" : "text/plain";
            }
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (guess != null) return guess;
        }
        return "application/octet-stream";
    }

    private static String enc(String value) {
        return Uri.encode(value, "");
    }

    private static java.nio.charset.Charset charset() {
        return java.nio.charset.Charset.forName("UTF-8");
    }
}
