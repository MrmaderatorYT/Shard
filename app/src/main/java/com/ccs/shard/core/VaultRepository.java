package com.ccs.shard.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single entry point the UI uses to talk to the vault.
 *
 * <p>Threading contract: every public method is safe to call from the main
 * thread and never touches the disk there, with two deliberate exceptions —
 * {@link #createNote} and the path helpers do a handful of {@code stat} calls so
 * a new note can be opened without a round trip. Reads and writes run on the
 * single serial disk executor, so a save can never interleave with another save
 * of the same file.
 *
 * <p>State lives in {@link NoteIndex}; mutations publish a change notification
 * so open screens can refresh without polling.
 */
public final class VaultRepository {

    private static final String TAG = "ShardVault";

    private static volatile VaultRepository instance;

    private final Context appContext;
    private final Vault vault;
    private final NoteIndex index;
    private final VersionManager versions;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean opened;
    private volatile boolean opening;

    public interface Listener {
        /** Called on the main thread whenever the note set or its metadata changed. */
        void onVaultChanged();
    }

    private VaultRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.vault = new Vault(appContext);
        this.index = new NoteIndex(vault);
        this.versions = new VersionManager(vault.versionsDir());
    }

    public static VaultRepository get(Context context) {
        VaultRepository local = instance;
        if (local == null) {
            synchronized (VaultRepository.class) {
                local = instance;
                if (local == null) {
                    local = new VaultRepository(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public Vault vault() { return vault; }

    public NoteIndex index() { return index; }

    public VersionManager versions() { return versions; }

    public boolean isOpened() { return opened; }

    /**
     * True when a folder inside the vault could not be listed or read, for example
     * after shared-storage access was revoked or while a sync client holds a lock.
     */
    public boolean hasInaccessibleFiles() {
        return index.unreadableCount() > 0;
    }

    // ---------------------------------------------------------------- lifecycle

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        // Renames, moves, trashing and folder operations all land here, and
        // none of them go through NoteFile. This is the only signal they share.
        VaultWrites.notifyStructureChanged();
        Io.onMain(new Runnable() {
            @Override public void run() {
                for (Listener listener : listeners) {
                    try {
                        listener.onVaultChanged();
                    } catch (Throwable t) {
                        Log.w(TAG, "listener failed", t);
                    }
                }
            }
        });
    }

    /**
     * Loads the cached index, then rescans the tree. Cheap to call repeatedly —
     * a warm vault only re-reads files whose mtime or size moved.
     */
    public void open(final Runnable onReady) {
        if (opened) {
            if (onReady != null) Io.onMain(onReady);
            refresh();
            return;
        }
        if (opening) {
            if (onReady != null) {
                // Deliver once the in-flight open completes.
                addListener(new Listener() {
                    @Override public void onVaultChanged() {
                        removeListener(this);
                        onReady.run();
                    }
                });
            }
            return;
        }
        opening = true;
        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    index.loadCache();
                    int parsed = index.scan();
                    // A vault left behind by Shard 1.x is copied in as soon as it
                    // becomes readable, which also covers the user granting
                    // All-files access after the fact.
                    if (vault.legacyState() == Vault.LegacyState.READABLE
                            && vault.importLegacyVault() > 0) {
                        parsed += index.scan();
                    }
                    if (parsed > 0) index.saveCache();
                    Log.i(TAG, "vault opened: " + index.size() + " notes, "
                            + parsed + " parsed, " + index.scannedFileCount() + " files seen, "
                            + index.unreadableCount() + " unreadable, "
                            + vault.importedLegacyNotes() + " imported from 1.x, root="
                            + vault.root());
                } catch (Throwable t) {
                    Log.e(TAG, "vault open failed", t);
                } finally {
                    opened = true;
                    opening = false;
                }
                if (onReady != null) Io.onMain(onReady);
                notifyChanged();
            }
        });
    }

    /** Rescans in the background; notifies only when something actually changed. */
    public void refresh() {
        Io.onDisk(new Runnable() {
            @Override public void run() {
                int before = index.size();
                long stamp = signature();
                vault.invalidateLegacyState();
                if (vault.legacyState() == Vault.LegacyState.READABLE) {
                    vault.importLegacyVault();
                }
                int parsed = index.scan();
                if (parsed > 0 || before != index.size() || stamp != signature()) {
                    index.saveCache();
                    notifyChanged();
                }
            }
        });
    }

    private long signature() {
        long sum = 0;
        for (Note note : index.all()) {
            sum = sum * 31 + note.getModifiedMillis();
        }
        return sum;
    }

    /** Persists the index cache; call from {@code onStop} of the last screen. */
    public void flush() {
        Io.onDisk(new Runnable() {
            @Override public void run() { index.saveCache(); }
        });
    }

    // ---------------------------------------------------------------- reading

    public List<Note> notes() { return index.all(); }

    public Note meta(String id) { return index.get(id); }

    public File fileOf(String id) { return vault.resolve(id); }

    /** Reads a note including its body. */
    public void loadNote(final String id, final Io.Result<Note> callback) {
        Io.load(new Io.Task<Note>() {
            @Override public Note run() throws Exception {
                return loadNoteSync(id);
            }
        }, callback);
    }

    /** Blocking read; only call from a background thread. */
    public Note loadNoteSync(String id) throws IOException {
        File file = vault.resolve(id);
        if (!file.exists()) {
            Note placeholder = index.get(id);
            if (placeholder != null) {
                Note copy = placeholder;
                copy.setContent("");
                return copy;
            }
            throw new IOException("note not found: " + id);
        }
        return NoteFile.read(file, id);
    }

    // ---------------------------------------------------------------- writing

    /**
     * Creates a note immediately and returns it so the editor can open without
     * waiting for the disk. The file itself is written on the disk executor.
     *
     * @param title  desired title; a numeric suffix is added if it is taken
     * @param folder vault-relative folder, or {@code ""} for the root
     */
    public Note createNote(String title, String folder, String initialContent) {
        return createNoteWithExtension(title, folder, initialContent, NoteFile.EXT);
    }

    /** Creates a raw TeX source file that remains a {@code .tex} file in the vault. */
    public Note createTexNote(String title, String folder, String initialContent) {
        return createNoteWithExtension(title, folder, initialContent, NoteFile.TEX_EXT);
    }

    private Note createNoteWithExtension(String title, String folder, String initialContent,
                                         String extension) {
        String safeTitle = Md.safeFileName(title);
        String dir = (folder == null) ? "" : folder;
        String id = uniqueId(dir, safeTitle, extension);

        final Note note = new Note(id, NoteFile.titleFromPath(id));
        note.setCreatedMillis(System.currentTimeMillis());
        note.setContent(initialContent == null ? "" : initialContent);
        NoteFile.indexBody(note, note.getContent());
        index.put(note);
        notifyChanged();

        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    File file = vault.resolve(note.getId());
                    NoteFile.save(file, note);
                    index.put(note);
                } catch (Throwable t) {
                    Log.e(TAG, "cannot create " + note.getId(), t);
                }
            }
        });
        return note;
    }

    /** Picks a free {@code folder/Title.md}, appending " 2", " 3", … when needed. */
    private String uniqueId(String folder, String baseTitle) {
        return uniqueId(folder, baseTitle, NoteFile.EXT);
    }

    /** Picks a free id while retaining a non-Markdown source file's extension. */
    private String uniqueId(String folder, String baseTitle, String extension) {
        String prefix = folder.isEmpty() ? "" : folder + "/";
        String candidate = prefix + baseTitle + extension;
        if (!vault.resolve(candidate).exists() && !index.contains(candidate)) return candidate;
        for (int n = 2; n < 1000; n++) {
            candidate = prefix + baseTitle + " " + n + extension;
            if (!vault.resolve(candidate).exists() && !index.contains(candidate)) return candidate;
        }
        return prefix + baseTitle + " " + System.currentTimeMillis() + extension;
    }

    /**
     * Writes {@code note} to disk. Metadata (tags, links, excerpt) is recomputed
     * from the body first so the index and the graph stay truthful.
     *
     * @param takeSnapshot record a version-history entry (throttled internally)
     */
    public void save(final Note note, final boolean takeSnapshot) {
        if (note == null || note.getId() == null || !note.isLoaded()) return;
        // Recompute derived metadata on the caller's thread: it is a linear scan
        // of text already in memory, and it keeps the in-memory index correct
        // even before the write lands.
        note.getTags().clear();
        NoteFile.indexBody(note, note.getContent());
        note.touch();
        index.put(note);
        notifyChanged();

        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    if (takeSnapshot) {
                        versions.snapshot(note.getId(), note.getTitle(), note.getContent(), false);
                    }
                    File file = vault.resolve(note.getId());
                    NoteFile.save(file, note);
                    index.put(note);
                    index.saveCache();
                } catch (Throwable t) {
                    Log.e(TAG, "cannot save " + note.getId(), t);
                }
            }
        });
    }

    public void save(Note note) { save(note, true); }

    /** Persists only front-matter flags of an indexed note, leaving the body alone. */
    public void updateMeta(final Note indexedNote) {
        if (indexedNote == null || indexedNote.getId() == null) return;
        index.put(indexedNote);
        notifyChanged();
        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    File file = vault.resolve(indexedNote.getId());
                    if (!file.exists()) return;
                    Note onDisk = NoteFile.read(file, indexedNote.getId());
                    onDisk.setPinned(indexedNote.isPinned());
                    onDisk.setBookmarked(indexedNote.isBookmarked());
                    onDisk.setArchived(indexedNote.isArchived());
                    onDisk.setColor(indexedNote.getColor());
                    onDisk.setEmoji(indexedNote.getEmoji());
                    NoteFile.save(file, onDisk);
                    index.put(onDisk);
                    index.saveCache();
                } catch (Throwable t) {
                    Log.e(TAG, "cannot update meta for " + indexedNote.getId(), t);
                }
            }
        });
    }

    /** A mutation applied to a fully loaded note during a bulk operation. */
    public interface BulkMutation {
        /** Returns true when the note should be written back to disk. */
        boolean apply(Note note) throws Exception;
    }

    /** Applies content/front-matter changes serially and rescans the index once. */
    public void bulkEdit(final List<String> ids, final BulkMutation mutation,
                         final Io.Result<Integer> callback) {
        final List<String> copy = ids == null
                ? new ArrayList<String>() : new ArrayList<>(ids);
        Io.load(new Io.Task<Integer>() {
            @Override public Integer run() throws Exception {
                int changed = 0;
                if (mutation != null) {
                    for (String id : copy) {
                        if (id == null) continue;
                        File file = vault.resolve(id);
                        if (!file.isFile()) continue;
                        Note note = NoteFile.read(file, id);
                        if (!mutation.apply(note)) continue;
                        NoteFile.save(file, note);
                        changed++;
                    }
                }
                if (changed > 0) {
                    index.scan();
                    index.saveCache();
                    notifyChanged();
                }
                return changed;
            }
        }, callback);
    }

    /** Moves several notes in one serial disk operation. */
    public void bulkMove(final List<String> ids, final String targetFolder,
                         final Io.Result<Integer> callback) {
        final List<String> copy = ids == null
                ? new ArrayList<String>() : new ArrayList<>(ids);
        final String folder = targetFolder == null ? "" : targetFolder;
        Io.load(new Io.Task<Integer>() {
            @Override public Integer run() {
                int moved = 0;
                for (String id : copy) {
                    Note note = id == null ? null : index.get(id);
                    if (note == null || folder.equals(note.folder())) continue;
                    String newId = uniqueId(folder, note.getTitle(),
                            NoteFile.noteExtension(id));
                    File from = vault.resolve(id);
                    File to = vault.resolve(newId);
                    File parent = to.getParentFile();
                    if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    if (!from.exists() || !from.renameTo(to)) continue;
                    versions.reid(id, newId);
                    moved++;
                }
                if (moved > 0) {
                    index.scan();
                    index.saveCache();
                    notifyChanged();
                }
                return moved;
            }
        }, callback);
    }

    /** Moves several notes to the recoverable trash. */
    public void bulkDelete(final List<String> ids, final Io.Result<Integer> callback) {
        final List<String> copy = ids == null
                ? new ArrayList<String>() : new ArrayList<>(ids);
        Io.load(new Io.Task<Integer>() {
            @Override public Integer run() {
                int deleted = 0;
                for (String id : copy) {
                    if (id == null) continue;
                    File from = vault.resolve(id);
                    if (!from.isFile()) continue;
                    File trashFile = new File(vault.trashDir(),
                            System.currentTimeMillis() + "__" + id.replace('/', '~'));
                    if (from.renameTo(trashFile)) deleted++;
                }
                if (deleted > 0) {
                    index.scan();
                    index.saveCache();
                    notifyChanged();
                }
                return deleted;
            }
        }, callback);
    }

    /**
     * Renames a note's file and, optionally, rewrites {@code [[wiki links]]} in
     * every note that pointed at the old title — the behaviour people expect
     * from a linked-notes app.
     *
     * @return the new id, or null when the target name is taken
     */
    public String rename(final Note note, String newTitle, final boolean updateLinks) {
        if (note == null || newTitle == null) return null;
        final String oldId = note.getId();
        final String oldTitle = note.getTitle();
        String safe = Md.safeFileName(newTitle);
        if (safe.equals(oldTitle)) return oldId;

        String folder = note.folder();
        final String extension = NoteFile.noteExtension(oldId);
        final String newId = uniqueId(folder, safe, extension);
        if (!newId.equals((folder.isEmpty() ? "" : folder + "/") + safe + extension)) {
            // uniqueId had to disambiguate, which means the name is taken.
            return null;
        }

        index.reid(oldId, newId);
        notifyChanged();

        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    File from = vault.resolve(oldId);
                    File to = vault.resolve(newId);
                    File parent = to.getParentFile();
                    if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    if (from.exists() && !from.renameTo(to)) {
                        Log.e(TAG, "rename failed: " + oldId + " -> " + newId);
                        index.reid(newId, oldId);
                        notifyChanged();
                        return;
                    }
                    versions.reid(oldId, newId);
                    if (updateLinks) {
                        rewriteLinks(oldTitle, NoteFile.titleFromPath(newId));
                    }
                    index.scan();
                    index.saveCache();
                } catch (Throwable t) {
                    Log.e(TAG, "rename failed", t);
                } finally {
                    notifyChanged();
                }
            }
        });
        return newId;
    }

    /** Replaces every {@code [[oldTitle]]} target with {@code newTitle}. */
    private void rewriteLinks(String oldTitle, String newTitle) {
        String oldKey = Note.linkKey(oldTitle);
        for (Note candidate : index.all()) {
            if (!candidate.getOutgoingLinks().contains(oldKey)) continue;
            File file = vault.resolve(candidate.getId());
            if (!file.exists()) continue;
            try {
                String raw = NoteFile.readText(file);
                String updated = replaceWikiTarget(raw, oldKey, newTitle);
                if (!updated.equals(raw)) {
                    NoteFile.writeAtomic(file, updated);
                }
            } catch (Throwable t) {
                Log.w(TAG, "cannot rewrite links in " + candidate.getId(), t);
            }
        }
    }

    private static String replaceWikiTarget(String text, String oldKey, String newTitle) {
        java.util.regex.Matcher m = Md.WIKI_LINK.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String target = m.group(1);
            if (!Note.linkKey(target).equals(oldKey)) continue;
            out.append(text, last, m.start());
            out.append("[[").append(newTitle);
            if (m.group(2) != null) out.append('#').append(m.group(2));
            if (m.group(3) != null) out.append('|').append(m.group(3));
            out.append("]]");
            last = m.end();
            changed = true;
        }
        if (!changed) return text;
        out.append(text, last, text.length());
        return out.toString();
    }

    /** Moves a note into {@code targetFolder} ({@code ""} = vault root). */
    public String move(final Note note, String targetFolder) {
        if (note == null) return null;
        final String oldId = note.getId();
        String folder = targetFolder == null ? "" : targetFolder;
        if (folder.equals(note.folder())) return oldId;
        final String newId = uniqueId(folder, note.getTitle(),
                NoteFile.noteExtension(oldId));

        index.reid(oldId, newId);
        notifyChanged();
        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    File from = vault.resolve(oldId);
                    File to = vault.resolve(newId);
                    File parent = to.getParentFile();
                    if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    if (from.exists() && !from.renameTo(to)) {
                        index.reid(newId, oldId);
                    } else {
                        versions.reid(oldId, newId);
                    }
                    index.saveCache();
                } catch (Throwable t) {
                    Log.e(TAG, "move failed", t);
                } finally {
                    notifyChanged();
                }
            }
        });
        return newId;
    }

    public Note duplicate(Note source) {
        if (source == null) return null;
        String body = source.isLoaded() ? source.getContent() : null;
        if (body == null) {
            try {
                body = NoteFile.read(vault.resolve(source.getId()), source.getId()).getContent();
            } catch (Throwable t) {
                body = "";
            }
        }
        Note copy = createNoteWithExtension(source.getTitle() + " copy", source.folder(), body,
                NoteFile.noteExtension(source.getId()));
        copy.setColor(source.getColor());
        copy.setEmoji(source.getEmoji());
        return copy;
    }

    // ---------------------------------------------------------------- deleting

    /** Moves a note to the vault trash. Recoverable until the trash is emptied. */
    public void delete(final Note note) {
        if (note == null) return;
        final String id = note.getId();
        index.remove(id);
        notifyChanged();
        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    File from = vault.resolve(id);
                    if (!from.exists()) return;
                    File trashFile = new File(vault.trashDir(),
                            System.currentTimeMillis() + "__" + id.replace('/', '~'));
                    if (!from.renameTo(trashFile)) {
                        Log.e(TAG, "cannot trash " + id);
                    }
                    index.saveCache();
                } catch (Throwable t) {
                    Log.e(TAG, "delete failed", t);
                }
            }
        });
    }

    /** Notes currently in the trash, newest first. */
    public List<TrashEntry> trash() {
        List<TrashEntry> out = new ArrayList<>();
        File[] files = vault.trashDir().listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (file.isDirectory()) continue;
            String name = file.getName();
            int split = name.indexOf("__");
            long deletedAt = file.lastModified();
            String originalId = name;
            if (split > 0) {
                try {
                    deletedAt = Long.parseLong(name.substring(0, split));
                } catch (Throwable ignored) { }
                originalId = name.substring(split + 2).replace('~', '/');
            }
            out.add(new TrashEntry(file, originalId, deletedAt));
        }
        Collections.sort(out, new java.util.Comparator<TrashEntry>() {
            @Override public int compare(TrashEntry a, TrashEntry b) {
                return Long.compare(b.deletedAt, a.deletedAt);
            }
        });
        return out;
    }

    public void restoreFromTrash(final TrashEntry entry, final Runnable onDone) {
        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    restoreTrashEntry(entry);
                    index.scan();
                    index.saveCache();
                } catch (Throwable t) {
                    Log.e(TAG, "restore failed", t);
                }
                notifyChanged();
                if (onDone != null) Io.onMain(onDone);
            }
        });
    }

    /** Restores several trash entries in one serial disk operation. */
    public void bulkRestoreFromTrash(final List<TrashEntry> entries,
                                     final Io.Result<Integer> callback) {
        final List<TrashEntry> copy = entries == null
                ? new ArrayList<TrashEntry>() : new ArrayList<>(entries);
        Io.load(new Io.Task<Integer>() {
            @Override public Integer run() {
                int restored = 0;
                for (TrashEntry entry : copy) {
                    if (restoreTrashEntry(entry)) restored++;
                }
                if (restored > 0) {
                    index.scan();
                    index.saveCache();
                    notifyChanged();
                }
                return restored;
            }
        }, callback);
    }

    /** Permanently removes selected entries from the trash. */
    public void deleteTrashEntries(final List<TrashEntry> entries,
                                   final Io.Result<Integer> callback) {
        final List<TrashEntry> copy = entries == null
                ? new ArrayList<TrashEntry>() : new ArrayList<>(entries);
        Io.load(new Io.Task<Integer>() {
            @Override public Integer run() {
                int deleted = 0;
                for (TrashEntry entry : copy) {
                    if (entry != null && entry.file.isFile() && entry.file.delete()) deleted++;
                }
                return deleted;
            }
        }, callback);
    }

    private boolean restoreTrashEntry(TrashEntry entry) {
        if (entry == null || !entry.file.isFile()) return false;
        String targetId = entry.originalId;
        if (vault.resolve(targetId).exists()) {
            String title = NoteFile.titleFromPath(targetId);
            int slash = targetId.lastIndexOf('/');
            String folder = slash < 0 ? "" : targetId.substring(0, slash);
            targetId = uniqueId(folder, title + " restored",
                    NoteFile.noteExtension(entry.originalId));
        }
        File to = vault.resolve(targetId);
        File parent = to.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        return entry.file.renameTo(to);
    }

    public void emptyTrash(final Runnable onDone) {
        Io.onDisk(new Runnable() {
            @Override public void run() {
                File[] files = vault.trashDir().listFiles();
                if (files != null) {
                    for (File file : files) //noinspection ResultOfMethodCallIgnored
                        file.delete();
                }
                if (onDone != null) Io.onMain(onDone);
            }
        });
    }

    /** Deletes trash entries older than {@code days}. Called on app start. */
    public void pruneTrash(final int days) {
        Io.onDisk(new Runnable() {
            @Override public void run() {
                long cutoff = System.currentTimeMillis() - days * 24L * 3600_000L;
                for (TrashEntry entry : trash()) {
                    if (entry.deletedAt < cutoff) //noinspection ResultOfMethodCallIgnored
                        entry.file.delete();
                }
            }
        });
    }

    // ---------------------------------------------------------------- folders

    public boolean createFolder(String parentFolder, String name) {
        String safe = Md.safeFileName(name);
        String path = (parentFolder == null || parentFolder.isEmpty()) ? safe : parentFolder + "/" + safe;
        File dir = vault.resolve(path);
        if (dir.exists()) return false;
        boolean made = dir.mkdirs();
        if (made) notifyChanged();
        return made;
    }

    public void renameFolder(final String folderPath, final String newName, final Runnable onDone) {
        Io.onDisk(new Runnable() {
            @Override public void run() {
                File from = vault.resolve(folderPath);
                int slash = folderPath.lastIndexOf('/');
                String parent = slash < 0 ? "" : folderPath.substring(0, slash);
                String safe = Md.safeFileName(newName);
                File to = vault.resolve(parent.isEmpty() ? safe : parent + "/" + safe);
                if (from.isDirectory() && !to.exists()) //noinspection ResultOfMethodCallIgnored
                    from.renameTo(to);
                index.scan();
                index.saveCache();
                notifyChanged();
                if (onDone != null) Io.onMain(onDone);
            }
        });
    }

    /** Moves a folder's notes to the trash, then removes the folder. */
    public void deleteFolder(final String folderPath, final Runnable onDone) {
        Io.onDisk(new Runnable() {
            @Override public void run() {
                File dir = vault.resolve(folderPath);
                trashRecursive(dir);
                index.scan();
                index.saveCache();
                notifyChanged();
                if (onDone != null) Io.onMain(onDone);
            }
        });
    }

    private void trashRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    trashRecursive(child);
                } else if (NoteFile.isNoteFile(child.getName())) {
                    String id = vault.relativize(child);
                    File trashFile = new File(vault.trashDir(),
                            System.currentTimeMillis() + "__" + id.replace('/', '~'));
                    //noinspection ResultOfMethodCallIgnored
                    child.renameTo(trashFile);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    child.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------------------------------------------------------------- search

    /**
     * Searches titles, tags and bodies.
     *
     * <p>Title and tag matches come straight from the index and are ranked
     * highest. Body matches require reading files, so they run on the background
     * executor and stop after {@code limit} hits — enough for a search UI, and
     * bounded work on a slow device.
     */
    public void search(final String query, final int limit, final Io.Result<List<Hit>> callback) {
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            callback.onReady(new ArrayList<Hit>());
            return;
        }
        Io.load(new Io.Task<List<Hit>>() {
            @Override public List<Hit> run() {
                return searchSync(q, limit);
            }
        }, callback);
    }

    public List<Hit> searchSync(String query, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        List<Note> pending = new ArrayList<>();

        for (Note note : index.all()) {
            String title = note.getTitle() == null ? "" : note.getTitle().toLowerCase(Locale.ROOT);
            if (title.contains(needle)) {
                hits.add(new Hit(note, Hit.TITLE, note.getExcerpt()));
                continue;
            }
            boolean tagged = false;
            for (String tag : note.getTags()) {
                if (tag.toLowerCase(Locale.ROOT).contains(needle)) {
                    hits.add(new Hit(note, Hit.TAG, "#" + tag));
                    tagged = true;
                    break;
                }
            }
            if (!tagged) pending.add(note);
        }

        Collections.sort(hits, Hit.BY_RANK);

        java.util.Set<String> pendingIds = new java.util.HashSet<>();
        for (Note note : pending) pendingIds.add(note.getId());
        for (SearchIndex.Match match : index.searchBodies(query, Math.max(limit * 2, 80))) {
            if (hits.size() >= limit) break;
            if (!pendingIds.contains(match.noteId)) continue;
            Note note = index.get(match.noteId);
            if (note != null) hits.add(new Hit(note, Hit.BODY, match.snippet));
        }
        if (hits.size() > limit) return new ArrayList<>(hits.subList(0, limit));
        return hits;
    }

    /** A readable window around a body match, with Markdown syntax removed. */
    private static String snippet(String body, int at, int length) {
        int start = Math.max(0, at - 60);
        int end = Math.min(body.length(), at + length + 90);
        String text = Md.plainText(body.substring(start, end), 160);
        if (text.isEmpty()) return "";
        return (start > 0 ? "…" : "") + text + (end < body.length() ? "…" : "");
    }

    // ---------------------------------------------------------------- attachments

    /** Copies bytes into {@code attachments/} and returns the markdown-relative name. */
    public String importAttachment(String suggestedName, byte[] data) throws IOException {
        File dir = vault.attachmentsDir();
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        String base = Md.safeFileName(suggestedName);
        File target = new File(dir, base);
        int n = 2;
        while (target.exists() && n < 1000) {
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            String ext = dot > 0 ? base.substring(dot) : "";
            target = new File(dir, stem + "-" + n + ext);
            n++;
        }
        java.io.OutputStream out = new java.io.FileOutputStream(target);
        try {
            out.write(data);
        } finally {
            NoteFile.closeQuietly(out);
        }
        return "attachments/" + target.getName();
    }

    /** Resolves an image reference from note text to a file on disk. */
    public File resolveAttachment(String reference) {
        if (reference == null || reference.isEmpty()) return null;
        File direct = new File(reference);
        if (direct.isAbsolute() && direct.exists()) return direct;
        File inVault = vault.resolve(reference);
        if (inVault.exists()) return inVault;
        return new File(vault.attachmentsDir(), reference);
    }

    // ---------------------------------------------------------------- types

    /** A search result with the reason it matched. */
    public static final class Hit {
        public static final int TITLE = 0;
        public static final int TAG = 1;
        public static final int BODY = 2;

        public final Note note;
        public final int kind;
        public final String snippet;

        Hit(Note note, int kind, String snippet) {
            this.note = note;
            this.kind = kind;
            this.snippet = snippet == null ? "" : snippet;
        }

        static final java.util.Comparator<Hit> BY_RANK = new java.util.Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.kind != b.kind) return a.kind - b.kind;
                return Long.compare(b.note.getModifiedMillis(), a.note.getModifiedMillis());
            }
        };
    }

    /** A note sitting in the trash. */
    public static final class TrashEntry {
        public final File file;
        public final String originalId;
        public final long deletedAt;

        TrashEntry(File file, String originalId, long deletedAt) {
            this.file = file;
            this.originalId = originalId;
            this.deletedAt = deletedAt;
        }

        public String title() { return NoteFile.titleFromPath(originalId); }
    }
}
