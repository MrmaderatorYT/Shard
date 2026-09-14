package com.ccs.shard.core;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conflict-preserving two-way sync between the local vault and a SAF folder. */
public final class FolderSync {

    public static final class Result {
        public int pulled;
        public int pushed;
        public int conflicts;
        public int errors;

        public int changed() { return pulled + pushed; }
    }

    private static final class Stamp {
        long localTime;
        long localSize;
        long remoteTime;
        long remoteSize;
    }

    private final Context context;
    private final VaultRepository repository;
    private final ContentResolver resolver;
    private final File stateFile;

    public FolderSync(Context context, VaultRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.resolver = this.context.getContentResolver();
        this.stateFile = new File(repository.vault().sidecarDir(), "folder-sync.tsv");
    }

    /** Blocking; call through {@link Io#load}. */
    public Result sync(Uri treeUri) {
        Result result = new Result();
        DocumentFile remoteRoot = DocumentFile.fromTreeUri(context, treeUri);
        if (remoteRoot == null || !remoteRoot.canRead() || !remoteRoot.canWrite()) {
            result.errors++;
            return result;
        }

        Map<String, File> local = new HashMap<>();
        collectLocal(repository.vault().root(), "", local);
        Map<String, DocumentFile> remote = new HashMap<>();
        collectRemote(remoteRoot, "", remote);
        Map<String, Stamp> previous = readState();
        Set<String> paths = new HashSet<>();
        paths.addAll(local.keySet());
        paths.addAll(remote.keySet());

        for (String path : paths) {
            File localFile = local.get(path);
            DocumentFile remoteFile = remote.get(path);
            try {
                if (localFile == null) {
                    localFile = repository.vault().resolve(path);
                    copyRemoteToLocal(remoteFile, localFile);
                    result.pulled++;
                    continue;
                }
                if (remoteFile == null) {
                    remoteFile = ensureRemoteFile(remoteRoot, path);
                    copyLocalToRemote(localFile, remoteFile);
                    result.pushed++;
                    continue;
                }

                Stamp old = previous.get(path);
                boolean localChanged = old == null
                        ? differs(localFile.length(), remoteFile.length())
                        : localFile.lastModified() != old.localTime
                        || localFile.length() != old.localSize;
                boolean remoteChanged = old == null
                        ? differs(localFile.length(), remoteFile.length())
                        : remoteFile.lastModified() != old.remoteTime
                        || remoteFile.length() != old.remoteSize;

                if (!localChanged && !remoteChanged) continue;
                if (old == null && localFile.length() == remoteFile.length()) continue;

                if (localChanged && remoteChanged) {
                    if (old == null) {
                        // First meeting: the newer copy wins, the older one is retained locally.
                        if (remoteFile.lastModified() > localFile.lastModified()) {
                            copyLocalFile(localFile, conflictFile(localFile, "local"));
                            copyRemoteToLocal(remoteFile, localFile);
                            result.pulled++;
                        } else {
                            copyRemoteToLocal(remoteFile, conflictFile(localFile, "remote"));
                            copyLocalToRemote(localFile, remoteFile);
                            result.pushed++;
                        }
                    } else {
                        // Both sides changed since the last successful sync. Keep remote as a
                        // conflict note and keep the local file as the primary version.
                        copyRemoteToLocal(remoteFile, conflictFile(localFile, "remote"));
                        copyLocalToRemote(localFile, remoteFile);
                        result.pushed++;
                    }
                    result.conflicts++;
                } else if (localChanged) {
                    copyLocalToRemote(localFile, remoteFile);
                    result.pushed++;
                } else {
                    copyRemoteToLocal(remoteFile, localFile);
                    result.pulled++;
                }
            } catch (Throwable error) {
                result.errors++;
            }
        }

        // Re-read both trees after writes so the baseline describes the successful result.
        local.clear();
        remote.clear();
        collectLocal(repository.vault().root(), "", local);
        collectRemote(remoteRoot, "", remote);
        writeState(local, remote);
        repository.refresh();
        return result;
    }

    private static boolean differs(long a, long b) { return a != b; }

    private void collectLocal(File directory, String prefix, Map<String, File> out) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.getName().equals(".shard") || child.getName().equals(".git")
                    || child.getName().endsWith(".tmp")) continue;
            String path = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) collectLocal(child, path, out);
            else out.put(path, child);
        }
    }

    private void collectRemote(DocumentFile directory, String prefix,
                               Map<String, DocumentFile> out) {
        DocumentFile[] children;
        try { children = directory.listFiles(); }
        catch (Throwable error) { return; }
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null || name.equals(".shard") || name.equals(".git")
                    || name.endsWith(".tmp")) continue;
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (child.isDirectory()) collectRemote(child, path, out);
            else if (child.isFile()) out.put(path, child);
        }
    }

    private DocumentFile ensureRemoteFile(DocumentFile root, String path) throws Exception {
        String[] parts = path.split("/");
        DocumentFile directory = root;
        for (int i = 0; i < parts.length - 1; i++) {
            DocumentFile next = directory.findFile(parts[i]);
            if (next == null) next = directory.createDirectory(parts[i]);
            if (next == null || !next.isDirectory()) throw new java.io.IOException("folder");
            directory = next;
        }
        DocumentFile file = directory.findFile(parts[parts.length - 1]);
        if (file == null) file = directory.createFile(mime(parts[parts.length - 1]),
                parts[parts.length - 1]);
        if (file == null) throw new java.io.IOException("file");
        return file;
    }

    private void copyLocalToRemote(File from, DocumentFile to) throws Exception {
        InputStream input = new FileInputStream(from);
        OutputStream output = resolver.openOutputStream(to.getUri(), "wt");
        if (output == null) { input.close(); throw new java.io.IOException("remote output"); }
        copy(input, output);
    }

    private void copyRemoteToLocal(DocumentFile from, File to) throws Exception {
        File parent = to.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream input = resolver.openInputStream(from.getUri());
        if (input == null) throw new java.io.IOException("remote input");
        OutputStream output = new FileOutputStream(to);
        copy(input, output);
        if (from.lastModified() > 0) to.setLastModified(from.lastModified());
    }

    private static void copyLocalFile(File from, File to) throws Exception {
        File parent = to.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        copy(new FileInputStream(from), new FileOutputStream(to));
        to.setLastModified(from.lastModified());
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
        } finally {
            try { input.close(); } catch (Throwable ignored) {}
            try { output.close(); } catch (Throwable ignored) {}
        }
    }

    private static File conflictFile(File original, String side) {
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new java.util.Date());
        return new File(original.getParentFile(), stem + ".conflict-" + side + "-" + stamp + ext);
    }

    private Map<String, Stamp> readState() {
        Map<String, Stamp> out = new HashMap<>();
        if (!stateFile.exists()) return out;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(stateFile));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\\t", -1);
                if (p.length != 5) continue;
                Stamp stamp = new Stamp();
                stamp.localTime = Long.parseLong(p[1]);
                stamp.localSize = Long.parseLong(p[2]);
                stamp.remoteTime = Long.parseLong(p[3]);
                stamp.remoteSize = Long.parseLong(p[4]);
                out.put(p[0], stamp);
            }
            reader.close();
        } catch (Throwable ignored) {}
        return out;
    }

    private void writeState(Map<String, File> local, Map<String, DocumentFile> remote) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, File> entry : local.entrySet()) {
            DocumentFile remoteFile = remote.get(entry.getKey());
            if (remoteFile == null) continue;
            File localFile = entry.getValue();
            text.append(entry.getKey().replace('\t', '_')).append('\t')
                    .append(localFile.lastModified()).append('\t')
                    .append(localFile.length()).append('\t')
                    .append(remoteFile.lastModified()).append('\t')
                    .append(remoteFile.length()).append('\n');
        }
        try { NoteFile.writeAtomic(stateFile, text.toString()); }
        catch (Throwable ignored) {}
    }

    private static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(NoteFile.TEX_EXT)) return "application/x-tex";
        if (lower.endsWith(".canvas") || lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
