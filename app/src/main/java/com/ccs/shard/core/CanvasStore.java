package com.ccs.shard.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds, creates and saves {@code .canvas} files in the vault.
 *
 * <p>Canvases live alongside notes as ordinary files, so a backup or a folder
 * sync carries them without any special handling. They are deliberately excluded
 * from the note index — a canvas is not a note, and showing it in the note list
 * as a blob of JSON would be worse than not showing it at all.
 */
public final class CanvasStore {

    public static final String EXT = ".canvas";
    private static final int MAX_DEPTH = 32;
    private static final int MAX_CANVASES = 20_000;

    private final Vault vault;

    public CanvasStore(Vault vault) {
        this.vault = vault;
    }

    /** One canvas file in the vault. */
    public static final class Entry {
        public final String id;
        public final String title;
        public final long modified;
        public final long sizeBytes;

        Entry(String id, String title, long modified, long sizeBytes) {
            this.id = id;
            this.title = title;
            this.modified = modified;
            this.sizeBytes = sizeBytes;
        }
    }

    /** Every canvas in the vault, newest first. */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        collect(vault.notesRoot(), out, 0);
        Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return Long.compare(b.modified, a.modified);
            }
        });
        return out;
    }

    private void collect(File dir, List<Entry> out, int depth) {
        if (depth > MAX_DEPTH || out.size() >= MAX_CANVASES) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= MAX_CANVASES) break;
            String name = child.getName();
            if (child.isDirectory()) {
                if (!Vault.isHidden(name)) collect(child, out, depth + 1);
                continue;
            }
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(EXT)
                    || name.startsWith(".")) continue;
            String id = vault.relativize(child);
            out.add(new Entry(id, titleOf(id), child.lastModified(), child.length()));
        }
    }

    public static String titleOf(String relativePath) {
        String name = relativePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.endsWith(EXT)) name = name.substring(0, name.length() - EXT.length());
        return name;
    }

    /** Creates an empty canvas, returning its vault-relative id. */
    public String create(String title, String folder) throws IOException {
        String safe = Md.safeFileName(title);
        String prefix = (folder == null || folder.isEmpty()) ? "" : folder + "/";
        String id = prefix + safe + EXT;
        File file = canvasFile(id);
        int n = 2;
        while (file.exists()) {
            if (n >= 10_000) throw new IOException("Too many canvases named " + safe);
            id = prefix + safe + " " + n + EXT;
            file = canvasFile(id);
            n++;
        }
        NoteFile.writeAtomic(file, "{\n \"nodes\": [],\n \"edges\": []\n}");
        return id;
    }

    public String read(String id) throws IOException {
        File file = canvasFile(id);
        if (!file.exists()) return "";
        return NoteFile.readText(file);
    }

    public void write(String id, String json) throws IOException {
        NoteFile.writeAtomic(canvasFile(id), json);
    }

    public boolean contains(String id) {
        try {
            return id != null && canvasFile(id).isFile();
        } catch (IOException ignored) {
            return false;
        }
    }

    public boolean delete(String id) {
        try {
            File file = canvasFile(id);
            return file.isFile() && file.delete();
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Renames a canvas; returns the new id, or null when the name is taken. */
    public String rename(String id, String newTitle) {
        try {
            File from = canvasFile(id);
            if (!from.exists()) return null;
            int slash = id.lastIndexOf('/');
            String folder = slash < 0 ? "" : id.substring(0, slash);
            String prefix = folder.isEmpty() ? "" : folder + "/";
            String newId = prefix + Md.safeFileName(newTitle) + EXT;
            if (newId.equals(id)) return id;
            File to = canvasFile(newId);
            if (to.exists()) return null;
            return from.renameTo(to) ? newId : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Resolves only vault-contained .canvas paths; callers can never escape the vault. */
    private File canvasFile(String id) throws IOException {
        if (id == null || !id.toLowerCase(java.util.Locale.ROOT).endsWith(EXT)) {
            throw new IOException("Invalid canvas id");
        }
        File root = vault.notesRoot().getCanonicalFile();
        File file = vault.resolve(id).getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!file.getPath().startsWith(rootPath)) {
            throw new IOException("Canvas is outside the vault");
        }
        return file;
    }
}
