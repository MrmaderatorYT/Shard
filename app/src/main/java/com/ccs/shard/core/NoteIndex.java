package com.ccs.shard.core;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory index of the whole vault: metadata for every note, plus tag and
 * link lookup tables.
 *
 * <p>This is the single structure the UI reads from, and it is what makes the
 * app usable on a 3 GB device. Two properties do the work:
 *
 * <ul>
 *   <li><b>Metadata only.</b> Note bodies are never retained here, so memory is
 *       proportional to note count, not vault size.</li>
 *   <li><b>Incremental rescan.</b> A persisted TSV cache is keyed by
 *       {@code (path, mtime, size)}. On launch we stat the tree and re-parse only
 *       files that actually changed, so a warm start is a few hundred
 *       {@code stat} calls rather than thousands of file reads.</li>
 * </ul>
 *
 * <p><b>Threading.</b> Reads come from the main thread while writes happen on the
 * disk executor, so every access to the maps is guarded. A full {@link #scan}
 * deliberately does <em>not</em> hold the lock while it reads files: it builds a
 * complete replacement off to the side and swaps it in at the end. Holding a lock
 * across a thousand file reads would block the UI for seconds; without any lock
 * at all, a rebuild throws {@link java.util.ConcurrentModificationException} in
 * the middle of the note list — which is exactly what happened on a 1200-note
 * vault before this was fixed.
 */
public final class NoteIndex {

    private static final String TAG = "ShardIndex";
    private static final int CACHE_VERSION = 5;
    /** Hard ceiling so a stray huge directory cannot exhaust memory. */
    private static final int MAX_NOTES = 20000;
    /** Separator between values inside one cached column (ASCII unit separator). */
    private static final char SEP = (char) 0x1F;

    private final Vault vault;
    private final SearchIndex searchIndex;
    /** Guards every map below. Held only for in-memory work, never across file IO. */
    private final Object lock = new Object();

    private Map<String, Note> byId = new LinkedHashMap<>();
    /** Lowercased title -> note ids sharing it (duplicate titles are legal). */
    private Map<String, List<String>> byLinkKey = new HashMap<>();
    /** Link target key -> ids of notes pointing at it, including unresolved targets. */
    private Map<String, Set<String>> backlinks = new HashMap<>();
    /** Tag -> note ids. */
    private Map<String, Set<String>> byTag = new HashMap<>();

    private volatile long lastScanMillis;
    private volatile int scannedFileCount;
    /** Note files present on disk that could not be read. */
    private volatile int unreadableCount;

    public NoteIndex(Vault vault) {
        this.vault = vault;
        this.searchIndex = new SearchIndex(vault.context());
    }

    // ---------------------------------------------------------------- queries

    public int size() {
        synchronized (lock) {
            return byId.size();
        }
    }

    public Note get(String id) {
        synchronized (lock) {
            return byId.get(id);
        }
    }

    public boolean contains(String id) {
        synchronized (lock) {
            return byId.containsKey(id);
        }
    }

    public List<Note> all() {
        synchronized (lock) {
            return new ArrayList<>(byId.values());
        }
    }

    /** Notes directly inside {@code folder} ({@code ""} for the vault root). */
    public List<Note> inFolder(String folder) {
        synchronized (lock) {
            String target = folder == null ? "" : folder;
            List<Note> out = new ArrayList<>();
            for (Note note : byId.values()) {
                if (note.folder().equals(target)) out.add(note);
            }
            return out;
            }
    }

    /** Direct child folder names of {@code folder}, with their note counts. */
    public List<FolderEntry> foldersIn(String folder) {
        synchronized (lock) {
            String prefix = (folder == null || folder.isEmpty()) ? "" : folder + "/";
            Map<String, int[]> counts = new LinkedHashMap<>();
            for (Note note : byId.values()) {
                String noteFolder = note.folder();
                if (noteFolder.isEmpty()) continue;
                if (!noteFolder.startsWith(prefix)) continue;
                String rest = noteFolder.substring(prefix.length());
                if (rest.isEmpty()) continue;
                int slash = rest.indexOf('/');
                String child = slash < 0 ? rest : rest.substring(0, slash);
                int[] cell = counts.get(child);
                if (cell == null) counts.put(child, new int[]{1});
                else cell[0]++;
            }
            List<FolderEntry> out = new ArrayList<>(counts.size());
            for (Map.Entry<String, int[]> e : counts.entrySet()) {
                out.add(new FolderEntry(prefix + e.getKey(), e.getKey(), e.getValue()[0]));
            }
            Collections.sort(out, new java.util.Comparator<FolderEntry>() {
                @Override public int compare(FolderEntry a, FolderEntry b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            return out;
            }
    }

    /** Every folder path in the vault, sorted, for move and tree UIs. */
    public List<String> allFolders() {
        synchronized (lock) {
            Set<String> paths = new HashSet<>();
            for (Note note : byId.values()) {
                String folder = note.folder();
                while (!folder.isEmpty()) {
                    paths.add(folder);
                    int slash = folder.lastIndexOf('/');
                    folder = slash < 0 ? "" : folder.substring(0, slash);
                }
            }
            List<String> out = new ArrayList<>(paths);
            Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
            return out;
            }
    }

    /** Resolves a {@code [[wiki link]]} target to a note, or null when unresolved. */
    public Note resolveLink(String target) {
        synchronized (lock) {
            if (target == null || target.isEmpty()) return null;
            List<String> ids = byLinkKey.get(Note.linkKey(target));
            if (ids != null && !ids.isEmpty()) return byId.get(ids.get(0));
            // Fall back to a path-style link, e.g. [[folder/note]].
            Note direct = byId.get(target);
            if (direct != null) return direct;
            direct = byId.get(target + NoteFile.EXT);
            if (direct != null) return direct;
            return byId.get(target + NoteFile.TEX_EXT);
            }
    }

    /** Notes that link to {@code note}. */
    public List<Note> backlinksOf(Note note) {
        synchronized (lock) {
            if (note == null) return new ArrayList<>();
            Set<String> sources = backlinkSources(note);
            if (sources.isEmpty()) return new ArrayList<>();
            List<Note> out = new ArrayList<>(sources.size());
            for (String id : sources) {
                Note source = byId.get(id);
                if (source != null && !source.getId().equals(note.getId())) out.add(source);
            }
            return out;
            }
    }

    public int backlinkCount(Note note) {
        synchronized (lock) {
            if (note == null) return 0;
            return backlinkSources(note).size();
            }
    }

    /** Link targets of {@code note} that do not exist yet. */
    public List<String> unresolvedLinksOf(Note note) {
        synchronized (lock) {
            List<String> out = new ArrayList<>(0);
            if (note == null) return out;
            for (String key : note.getOutgoingLinks()) {
                if (!byLinkKey.containsKey(key)) out.add(key);
            }
            return out;
            }
    }

    public List<String> allTags() {
        synchronized (lock) {
            List<String> tags = new ArrayList<>(byTag.keySet());
            Collections.sort(tags, String.CASE_INSENSITIVE_ORDER);
            return tags;
            }
    }

    public Map<String, Integer> tagCounts() {
        synchronized (lock) {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (String tag : allTags()) {
                Set<String> ids = byTag.get(tag);
                out.put(tag, ids == null ? 0 : ids.size());
            }
            return out;
            }
    }

    public List<Note> withTag(String tag) {
        synchronized (lock) {
            Set<String> ids = byTag.get(tag);
            if (ids == null) return new ArrayList<>();
            List<Note> out = new ArrayList<>(ids.size());
            for (String id : ids) {
                Note note = byId.get(id);
                if (note != null) out.add(note);
            }
            return out;
            }
    }

    List<SearchIndex.Match> searchBodies(String query, int limit) {
        return searchIndex.search(query, limit);
    }

    public long lastScanMillis() { return lastScanMillis; }

    public int scannedFileCount() { return scannedFileCount; }

    /**
     * Note files the scanner found but could not open. On Android 11+ this is
     * almost always a vault copied in from elsewhere: the files exist, but
     * reading them needs All-files access. The UI uses this to explain the
     * situation instead of showing a mysteriously empty vault.
     */
    public int unreadableCount() { return unreadableCount; }

    // ---------------------------------------------------------------- mutation

    /** Adds or replaces a note, keeping every lookup table consistent. */
    public void put(Note note) {
        if (note != null && note.isLoaded()) {
            final Note searchable = note;
            Io.onDisk(() -> searchIndex.upsert(searchable));
        }
        synchronized (lock) {
            if (note == null || note.getId() == null) return;
            Note previous = byId.remove(note.getId());
            if (previous != null) unlink(previous);
            Note stored = stripBody(note);
            byId.put(stored.getId(), stored);
            link(stored);
            }
    }

    public void remove(String id) {
        Io.onDisk(() -> searchIndex.delete(id));
        synchronized (lock) {
            Note removed = byId.remove(id);
            if (removed != null) unlink(removed);
            }
    }

    /** Moves an existing entry to a new id without re-parsing the file. */
    public void reid(String oldId, String newId) {
        Io.onDisk(() -> searchIndex.reid(oldId, newId));
        synchronized (lock) {
            Note note = byId.remove(oldId);
            if (note == null) return;
            unlink(note);
            note.setId(newId);
            note.setTitle(NoteFile.titleFromPath(newId));
            byId.put(newId, note);
            link(note);
            }
    }

    public void clear() {
        Io.onDisk(searchIndex::clearAll);
        synchronized (lock) {
            byId.clear();
            byLinkKey.clear();
            backlinks.clear();
            byTag.clear();
            }
    }

    /**
     * Copies the note without its body. The index must not pin note text in
     * memory, and callers routinely hand us a fully loaded note after a save.
     */
    private static Note stripBody(Note source) {
        if (!source.isLoaded()) return source;
        Note copy = new Note();
        copy.setId(source.getId());
        copy.setTitle(source.getTitle());
        copy.setExcerpt(source.getExcerpt());
        if (source.hasStoredCreated()) copy.setCreatedMillis(source.getCreatedMillis());
        else copy.setCreatedMillisSilently(source.getCreatedMillis());
        copy.setModifiedMillis(source.getModifiedMillis());
        copy.setSizeBytes(source.getSizeBytes());
        copy.setTags(new ArrayList<>(source.getTags()));
        copy.setAliases(new ArrayList<>(source.getAliases()));
        copy.setOutgoingLinks(new ArrayList<>(source.getOutgoingLinks()));
        copy.setColor(source.getColor());
        copy.setEmoji(source.getEmoji());
        copy.setPinned(source.isPinned());
        copy.setArchived(source.isArchived());
        copy.setBookmarked(source.isBookmarked());
        copy.setWordCount(source.getWordCount());
        copy.setBlockTypeMask(source.getBlockTypeMask());
        copy.setFrontMatterExtra(new ArrayList<>(source.getFrontMatterExtra()));
        return copy;
    }

    private void link(Note note) {
        if (note == null) return;
        for (String key : linkKeys(note)) addLinkKey(byLinkKey, key, note.getId());

        for (String target : note.getOutgoingLinks()) {
            Set<String> sources = backlinks.get(target);
            if (sources == null) {
                sources = new HashSet<>(2);
                backlinks.put(target, sources);
            }
            sources.add(note.getId());
        }
        for (String tag : note.getTags()) {
            Set<String> tagged = byTag.get(tag);
            if (tagged == null) {
                tagged = new HashSet<>(2);
                byTag.put(tag, tagged);
            }
            tagged.add(note.getId());
        }
    }

    private void unlink(Note note) {
        for (String key : linkKeys(note)) removeLinkKey(byLinkKey, key, note.getId());
        for (String target : note.getOutgoingLinks()) {
            Set<String> sources = backlinks.get(target);
            if (sources != null) {
                sources.remove(note.getId());
                if (sources.isEmpty()) backlinks.remove(target);
            }
        }
        for (String tag : note.getTags()) {
            Set<String> tagged = byTag.get(tag);
            if (tagged != null) {
                tagged.remove(note.getId());
                if (tagged.isEmpty()) byTag.remove(tag);
            }
        }
    }

    private Set<String> backlinkSources(Note note) {
        Set<String> out = new HashSet<>();
        for (String key : linkKeys(note)) {
            Set<String> sources = backlinks.get(key);
            if (sources != null) out.addAll(sources);
        }
        return out;
    }

    /** Title plus all Obsidian aliases, normalised and deduplicated for link lookup. */
    private static List<String> linkKeys(Note note) {
        List<String> keys = new ArrayList<>();
        if (note == null) return keys;
        String title = note.linkKey();
        if (!title.isEmpty()) keys.add(title);
        for (String alias : note.getAliases()) {
            String key = Note.linkKey(alias);
            if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    private static void addLinkKey(Map<String, List<String>> map, String key, String id) {
        List<String> ids = map.get(key);
        if (ids == null) {
            ids = new ArrayList<>(1);
            map.put(key, ids);
        }
        if (!ids.contains(id)) ids.add(id);
    }

    private static void removeLinkKey(Map<String, List<String>> map, String key, String id) {
        List<String> ids = map.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) map.remove(key);
    }


    /**
     * A complete index built off to the side, then installed in one step.
     *
     * <p>This is what lets a rescan run for seconds without ever blocking a read:
     * the UI keeps seeing the previous, fully consistent index until the new one
     * is ready.
     */
    private static final class Snapshot {
        final Map<String, Note> byId = new LinkedHashMap<>();
        final Map<String, List<String>> byLinkKey = new HashMap<>();
        final Map<String, Set<String>> backlinks = new HashMap<>();
        final Map<String, Set<String>> byTag = new HashMap<>();

        void put(Note note) {
            if (note == null || note.getId() == null) return;
            Note stored = stripBody(note);
            byId.put(stored.getId(), stored);

            for (String key : linkKeys(stored)) addLinkKey(byLinkKey, key, stored.getId());

            for (String target : stored.getOutgoingLinks()) {
                Set<String> sources = backlinks.get(target);
                if (sources == null) {
                    sources = new HashSet<>(2);
                    backlinks.put(target, sources);
                }
                sources.add(stored.getId());
            }
            for (String tag : stored.getTags()) {
                Set<String> tagged = byTag.get(tag);
                if (tagged == null) {
                    tagged = new HashSet<>(2);
                    byTag.put(tag, tagged);
                }
                tagged.add(stored.getId());
            }
        }

        int size() { return byId.size(); }
    }

    /** Replaces the whole index in one locked step. */
    private void install(Snapshot snapshot) {
        synchronized (lock) {
            byId = snapshot.byId;
            byLinkKey = snapshot.byLinkKey;
            backlinks = snapshot.backlinks;
            byTag = snapshot.byTag;
        }
    }

    // ---------------------------------------------------------------- scanning

    /**
     * Walks the vault and refreshes the index. Files whose mtime and size match
     * the previous entry are not re-read.
     *
     * @return number of notes that had to be parsed from disk
     */
    public int scan() {
        android.os.Trace.beginSection("Shard.index.scan");
        try {
        Map<String, Note> previous;
        synchronized (lock) {
            previous = new HashMap<>(byId);
        }
        Snapshot snapshot = new Snapshot();
        int files = 0;
        int unreadable = 0;
        int parsed = 0;

        List<File> stack = new ArrayList<>();
        stack.add(vault.notesRoot());
        while (!stack.isEmpty()) {
            File dir = stack.remove(stack.size() - 1);
            File[] children = dir.listFiles();
            if (children == null) {
                // The directory exists but cannot be listed: on Android 11+ this is
                // a folder owned by another app or a previous install. Count it so
                // the UI can offer full file access instead of showing nothing.
                if (dir.exists()) unreadable++;
                continue;
            }
            for (File child : children) {
                String name = child.getName();
                if (child.isDirectory()) {
                    if (!Vault.isHidden(name)) stack.add(child);
                    continue;
                }
                if (name.startsWith(".") || !NoteFile.isNoteFile(name)) continue;
                if (snapshot.size() >= MAX_NOTES) {
                    Log.w(TAG, "note limit reached, ignoring the rest of the vault");
                    stack.clear();
                    break;
                }
                files++;
                String id = vault.relativize(child);
                Note cached = previous.get(id);
                if (cached != null
                        && cached.getModifiedMillis() == child.lastModified()
                        && cached.getSizeBytes() == child.length()) {
                    if (searchIndex.isCurrent(cached)) {
                        snapshot.put(cached);
                        continue;
                    }
                }
                try {
                    Note loaded = NoteFile.read(child, id);
                    searchIndex.upsert(loaded);
                    snapshot.put(loaded);
                    parsed++;
                } catch (Throwable t) {
                    unreadable++;
                    Log.w(TAG, "cannot read " + id, t);
                }
            }
        }

        install(snapshot);
        searchIndex.prune(snapshot.byId.keySet());
        scannedFileCount = files;
        unreadableCount = unreadable;
        lastScanMillis = System.currentTimeMillis();
        return parsed;
        } finally {
            android.os.Trace.endSection();
        }
    }

    // ---------------------------------------------------------------- cache

    /** Loads the persisted cache so the first scan mostly hits the mtime fast path. */
    public void loadCache() {
        File file = vault.indexCacheFile();
        if (!file.exists()) return;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(file), "UTF-8"), 8192);
            String header = reader.readLine();
            if (header == null || !header.equals("shard-index\t" + CACHE_VERSION)) return;
            Snapshot snapshot = new Snapshot();
            String line;
            while ((line = reader.readLine()) != null) {
                Note note = decode(line);
                if (note != null) snapshot.put(note);
            }
            install(snapshot);
        } catch (Throwable t) {
            Log.w(TAG, "index cache unreadable, will rebuild", t);
            clear();
        } finally {
            NoteFile.closeQuietly(reader);
        }
    }

    public void saveCache() {
        synchronized (lock) {
            File file = vault.indexCacheFile();
            BufferedWriter writer = null;
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(file), "UTF-8"), 8192);
                writer.write("shard-index\t" + CACHE_VERSION);
                writer.write('\n');
                for (Note note : byId.values()) {
                    writer.write(encode(note));
                    writer.write('\n');
                }
            } catch (Throwable t) {
                Log.w(TAG, "cannot write index cache", t);
            } finally {
                NoteFile.closeQuietly(writer);
            }
            }
    }

    private static String encode(Note n) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(esc(n.getId())).append('\t');
        sb.append(n.getModifiedMillis()).append('\t');
        sb.append(n.getSizeBytes()).append('\t');
        sb.append(n.getCreatedMillis()).append('\t');
        sb.append(n.getWordCount()).append('\t');
        sb.append(n.getBlockTypeMask()).append('\t');
        sb.append(n.getColor()).append('\t');
        sb.append(flags(n)).append('\t');
        sb.append(esc(n.getEmoji())).append('\t');
        sb.append(esc(join(n.getTags()))).append('\t');
        sb.append(esc(join(n.getOutgoingLinks()))).append('\t');
        sb.append(esc(join(n.getAliases()))).append('\t');
        sb.append(esc(join(n.getFrontMatterExtra()))).append('\t');
        sb.append(esc(n.getExcerpt()));
        return sb.toString();
    }

    private static Note decode(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 14) return null;
        try {
            Note n = new Note();
            n.setId(unesc(parts[0]));
            n.setTitle(NoteFile.titleFromPath(n.getId()));
            n.setModifiedMillis(Long.parseLong(parts[1]));
            n.setSizeBytes(Long.parseLong(parts[2]));
            n.setCreatedMillisSilently(Long.parseLong(parts[3]));
            n.setWordCount(Integer.parseInt(parts[4]));
            n.setBlockTypeMask(Integer.parseInt(parts[5]));
            n.setColor(Integer.parseInt(parts[6]));
            int flags = Integer.parseInt(parts[7]);
            n.setPinned((flags & 1) != 0);
            n.setArchived((flags & 2) != 0);
            n.setBookmarked((flags & 4) != 0);
            if ((flags & 8) != 0) n.setCreatedMillis(n.getCreatedMillis());
            n.setEmoji(unesc(parts[8]));
            n.setTags(split(unesc(parts[9])));
            n.setOutgoingLinks(split(unesc(parts[10])));
            n.setAliases(split(unesc(parts[11])));
            n.setFrontMatterExtra(split(unesc(parts[12])));
            n.setExcerpt(unesc(parts[13]));
            return n;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int flags(Note n) {
        int f = 0;
        if (n.isPinned()) f |= 1;
        if (n.isArchived()) f |= 2;
        if (n.isBookmarked()) f |= 4;
        if (n.hasStoredCreated()) f |= 8;
        return f;
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>(2);
        if (value == null || value.isEmpty()) return out;
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == SEP) {
                if (i > start) out.add(value.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        if (s == null) return "";
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                if (next == 't') sb.append('\t');
                else if (next == 'n') sb.append('\n');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A folder in the vault tree, with the number of notes it contains recursively. */
    public static final class FolderEntry {
        public final String path;
        public final String name;
        public final int noteCount;

        FolderEntry(String path, String name, int noteCount) {
            this.path = path;
            this.name = name;
            this.noteCount = noteCount;
        }
    }
}
