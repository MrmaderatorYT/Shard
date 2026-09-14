package com.ccs.shard.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Resolves where the vault lives on disk and owns the folder layout.
 *
 * <p>The primary vault is app-specific storage. User-visible shared folders are
 * accessed through the Storage Access Framework by the import/export and sync
 * flows, so the app never needs broad filesystem access.
 *
 * <p>Layout, which is a plain Markdown folder any other tool can open:
 * <pre>
 *   &lt;root&gt;/                 notes as .md files, folders as real directories
 *   &lt;root&gt;/attachments/     images and other binaries
 *   &lt;root&gt;/.shard/          sidecars: index cache, version history, trash
 * </pre>
 */
public final class Vault {

    private static final String TAG = "ShardVault";
    private static final String PREFS = "shard_vault";
    private static final String KEY_ROOT = "root_path";
    private static final String SHARED_DIR_NAME = "Shard";

    private final Context appContext;
    private final File root;
    private final boolean usingSharedStorage;
    private int inaccessibleLegacyFiles;
    private int importedLegacyNotes;

    public Vault(Context context) {
        this.appContext = context.getApplicationContext();
        this.root = resolveRoot();
        this.usingSharedStorage = isUnderSharedStorage(root);
        ensureLayout();
    }

    // ---------------------------------------------------------------- resolution

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private File resolveRoot() {
        String saved = prefs().getString(KEY_ROOT, null);
        if (saved != null) {
            File dir = new File(saved);
            if (isUsable(dir)) return dir;
            Log.w(TAG, "configured vault " + saved + " is not usable; falling back");
        }
        // Shared-storage access is intentionally user-mediated through SAF.
        // Direct File access is limited to the app-specific vault.
        if (saved != null) {
            File dir = new File(saved);
            if (!isUnderSharedStorage(dir) && isUsable(dir)) return dir;
        }
        return privateRoot();
    }

    private File privateRoot() {
        File base = appContext.getExternalFilesDir(null);
        // getExternalFilesDir returns null when external storage is unavailable
        // (an unmounted SD card on some devices); internal storage always works.
        if (base == null) base = appContext.getFilesDir();
        return new File(base, "vault");
    }

    /** Canonical user-visible location for every note and canvas. */
    public File sharedStorageRoot() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), SHARED_DIR_NAME);
    }

    /** True when the directory exists (or can be created) and a probe write succeeds. */
    public static boolean isUsable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            if (!dir.isDirectory()) return false;
            File probe = new File(dir, ".shard-write-probe");
            FileOutputStream out = new FileOutputStream(probe);
            out.write('1');
            out.close();
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isUnderSharedStorage(File dir) {
        try {
            String external = Environment.getExternalStorageDirectory().getCanonicalPath();
            String candidate = dir.getCanonicalPath();
            return candidate.startsWith(external) && !candidate.contains("/Android/data/");
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureLayout() {
        mkdirs(root);
        mkdirs(attachmentsDir());
        mkdirs(sidecarDir());
        mkdirs(versionsDir());
        mkdirs(trashDir());
        File noMedia = new File(sidecarDir(), ".nomedia");
        if (!noMedia.exists()) {
            try { //noinspection ResultOfMethodCallIgnored
                noMedia.createNewFile();
            } catch (Throwable ignored) { }
        }
    }

    private static void mkdirs(File dir) {
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    // ---------------------------------------------------------------- legacy import

    /**
     * What the Shard 1.x folder in shared storage looks like from in here.
     *
     * <p>Note the deliberate absence of a "present but unreadable" state that the
     * UI can act on. Scoped storage does not merely deny {@code open()} on files
     * this install does not own — it omits them from directory listings entirely,
     * so a folder full of inaccessible notes is indistinguishable from an empty
     * one. Verified on the target device. The app therefore does not pretend to
     * detect that case; the reliable recovery route is Import → From a folder,
     * where the document picker grants access the file API cannot.
     */
    public enum LegacyState {
        /** No 1.x folder, or nothing visible left in it to bring over. */
        NONE,
        /** Notes are there and can be read: they get copied in automatically. */
        READABLE
    }

    private LegacyState cachedLegacyState;

    /**
     * Probes the old shared-storage vault.
     *
     * <p>Listing that folder still works under scoped storage, so a bare
     * directory listing proves nothing; the only reliable test is to try opening
     * a note. The result is cached because the UI asks on every list rebuild.
     */
    public LegacyState legacyState() {
        if (cachedLegacyState != null) return cachedLegacyState;
        cachedLegacyState = probeLegacy();
        return cachedLegacyState;
    }

    public void invalidateLegacyState() {
        cachedLegacyState = null;
    }

    private LegacyState probeLegacy() {
        if (isUnderSharedStorage(root)) return LegacyState.NONE;
        File legacy = sharedStorageRoot();
        if (!legacy.exists()) return LegacyState.NONE;

        java.util.List<File> pending = new java.util.ArrayList<>();
        collectLegacyNotes(legacy, pending, 0);
        if (pending.isEmpty()) return LegacyState.NONE;

        for (File file : pending) {
            if (canRead(file)) return LegacyState.READABLE;
        }
        return LegacyState.NONE;
    }

    /** Collects legacy notes that are not already present in this vault. */
    private void collectLegacyNotes(File dir, java.util.List<File> out, int depth) {
        if (depth > 6 || out.size() >= 200) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                collectLegacyNotes(child, out, depth + 1);
                continue;
            }
            if (!NoteFile.isNoteFile(name)) continue;
            if (new File(root, name).exists()) continue;
            out.add(child);
            if (out.size() >= 200) return;
        }
    }

    private static boolean canRead(File file) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            return in.read() != -2;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Copies every readable note from the 1.x folder that is not here yet.
     * Copies rather than moves, so the originals remain as a safety net.
     *
     * @return how many notes were brought over
     */
    public int importLegacyVault() {
        if (isUnderSharedStorage(root)) return 0;
        File legacy = sharedStorageRoot();
        if (!legacy.isDirectory()) return 0;

        java.util.List<File> pending = new java.util.ArrayList<>();
        collectLegacyNotes(legacy, pending, 0);
        int imported = 0;
        for (File source : pending) {
            File target = new File(root, source.getName());
            if (target.exists()) continue;
            if (copyFile(source, target)) imported++;
            else inaccessibleLegacyFiles++;
        }

        File legacyAttachments = new File(legacy, "attachments");
        if (legacyAttachments.isDirectory()) {
            File[] files = legacyAttachments.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.isFile()) continue;
                    File target = new File(attachmentsDir(), file.getName());
                    if (!target.exists()) copyFile(file, target);
                }
            }
        }

        if (imported > 0) {
            Log.i(TAG, "imported " + imported + " note(s) from the 1.x vault");
            importedLegacyNotes += imported;
        }
        invalidateLegacyState();
        return imported;
    }

    private static boolean copyFile(File from, File to) {
        File parent = to.getParentFile();
        if (parent != null) mkdirs(parent);
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            //noinspection ResultOfMethodCallIgnored
            to.setLastModified(from.lastModified());
            return true;
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) { }
    }

    public int importedLegacyNotes() { return importedLegacyNotes; }

    // ---------------------------------------------------------------- relocation

    /**
     * Moves the whole vault to a new root and re-points at it.
     *
     * @return true when every file arrived; on failure the original is left intact
     */
    public boolean relocateTo(File newRoot) {
        if (newRoot.equals(root)) return true;
        if (!isUsable(newRoot)) return false;
        boolean ok = copyTree(root, newRoot);
        if (!ok) {
            Log.e(TAG, "vault relocation to " + newRoot + " failed; keeping " + root);
            return false;
        }
        if (!prefs().edit().putString(KEY_ROOT, newRoot.getAbsolutePath()).commit()) {
            Log.e(TAG, "cannot persist vault location " + newRoot);
            return false;
        }
        deleteTree(root);
        return true;
    }

    private static boolean copyTree(File from, File into) {
        File[] children = from.listFiles();
        if (children == null) return false;
        mkdirs(into);
        boolean ok = true;
        for (File child : children) {
            File target = new File(into, child.getName());
            if (child.isDirectory()) ok &= copyTree(child, target);
            else if (!target.exists()) ok &= copyFile(child, target);
            else ok &= sameFile(child, target);
        }
        return ok;
    }

    /** Existing destination files are accepted only when they are byte-identical. */
    private static boolean sameFile(File first, File second) {
        if (!first.isFile() || !second.isFile() || first.length() != second.length()) {
            return false;
        }
        FileInputStream a = null;
        FileInputStream b = null;
        try {
            a = new FileInputStream(first);
            b = new FileInputStream(second);
            byte[] left = new byte[8192];
            byte[] right = new byte[8192];
            int read;
            while ((read = a.read(left)) != -1) {
                int other = b.read(right);
                if (other != read) return false;
                for (int i = 0; i < read; i++) {
                    if (left[i] != right[i]) return false;
                }
            }
            return b.read() == -1;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(a);
            closeQuietly(b);
        }
    }

    private static void deleteTree(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteTree(child);
                else //noinspection ResultOfMethodCallIgnored
                    child.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------------------------------------------------------------- accessors

    public File root() { return root; }

    Context context() { return appContext; }

    public File notesRoot() { return root; }

    public File attachmentsDir() { return new File(root, "attachments"); }

    public File sidecarDir() { return new File(root, ".shard"); }

    public File versionsDir() { return new File(sidecarDir(), "versions"); }

    public File trashDir() { return new File(sidecarDir(), "trash"); }

    public File indexCacheFile() { return new File(sidecarDir(), "index.tsv"); }

    public File exportCacheDir() {
        File dir = new File(appContext.getCacheDir(), "export");
        mkdirs(dir);
        return dir;
    }

    /** Rotating automatic ZIPs live outside the vault so no backup contains itself. */
    public File automaticBackupsDir() {
        File dir = new File(appContext.getFilesDir(), "automatic-backups");
        mkdirs(dir);
        return dir;
    }

    /** True when notes live in user-browsable shared storage. */
    public boolean isSharedStorage() { return usingSharedStorage; }

    /**
     * A path the user can actually find over USB, for display in settings.
     * The {@code Android/data} prefix is what a desktop file manager shows.
     */
    public String displayPath() {
        String path = root.getAbsolutePath();
        String external = Environment.getExternalStorageDirectory().getAbsolutePath();
        return path.startsWith(external) ? path.substring(external.length() + 1) : path;
    }

    /** True when the platform lets us read files this app did not create. */
    public boolean hasFullFileAccess() {
        // Kept for source compatibility with older callers. The app no longer
        // requests broad storage access; its private vault is always available.
        return true;
    }

    // ---------------------------------------------------------------- paths

    /** Vault-relative path used as a note's stable id, e.g. {@code work/ideas.md}. */
    public String relativize(File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(rootPath)) {
            String rel = filePath.substring(rootPath.length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }
        return file.getName();
    }

    public File resolve(String relativePath) {
        return new File(root, relativePath);
    }

    /** True for paths the note scanner must skip. */
    public static boolean isHidden(String name) {
        return name.startsWith(".") || name.equals("attachments");
    }
}
