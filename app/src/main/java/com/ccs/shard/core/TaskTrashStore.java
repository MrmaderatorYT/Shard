package com.ccs.shard.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Recoverable trash for individual checklist lines, kept separate from note-file trash. */
public final class TaskTrashStore {

    private static final String FILE_NAME = "task-trash.json";

    private TaskTrashStore() { }

    public static final class Entry {
        public final String id;
        public final String noteId;
        public final String noteTitle;
        public final int lineIndex;
        public final String line;
        public final long deletedAt;

        private Entry(String id, String noteId, String noteTitle, int lineIndex, String line,
                      long deletedAt) {
            this.id = id;
            this.noteId = noteId;
            this.noteTitle = noteTitle;
            this.lineIndex = lineIndex;
            this.line = line;
            this.deletedAt = deletedAt;
        }

        static Entry of(String noteId, String noteTitle, int lineIndex, String line) {
            return new Entry(UUID.randomUUID().toString(), noteId, noteTitle, lineIndex,
                    line == null ? "" : line, System.currentTimeMillis());
        }

        private JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("noteId", noteId)
                    .put("noteTitle", noteTitle)
                    .put("lineIndex", lineIndex)
                    .put("line", line)
                    .put("deletedAt", deletedAt);
        }

        private static Entry fromJson(JSONObject value) {
            if (value == null) return null;
            String id = value.optString("id", "");
            String noteId = value.optString("noteId", "");
            if (id.isEmpty() || noteId.isEmpty()) return null;
            return new Entry(id, noteId, value.optString("noteTitle", ""),
                    value.optInt("lineIndex", 0), value.optString("line", ""),
                    value.optLong("deletedAt", 0));
        }
    }

    /** Blocking; call from the serial disk executor. */
    public static List<Entry> list(Vault vault) {
        List<Entry> entries = new ArrayList<>();
        if (vault == null) return entries;
        try {
            File file = file(vault);
            if (!file.isFile()) return entries;
            JSONArray values = new JSONArray(NoteFile.readText(file));
            for (int i = 0; i < values.length(); i++) {
                Entry entry = Entry.fromJson(values.optJSONObject(i));
                if (entry != null) entries.add(entry);
            }
        } catch (Throwable ignored) { }
        java.util.Collections.sort(entries, (a, b) -> Long.compare(b.deletedAt, a.deletedAt));
        return entries;
    }

    /** Adds deleted task lines without disturbing entries that have not yet been restored. */
    public static void append(Vault vault, List<Entry> additions) throws Exception {
        if (additions == null || additions.isEmpty()) return;
        List<Entry> all = list(vault);
        all.addAll(additions);
        write(vault, all);
    }

    /** Removes only entries that were successfully restored. */
    public static void remove(Vault vault, List<Entry> removed) throws Exception {
        if (removed == null || removed.isEmpty()) return;
        Set<String> ids = new HashSet<>();
        for (Entry entry : removed) if (entry != null) ids.add(entry.id);
        List<Entry> retained = new ArrayList<>();
        for (Entry entry : list(vault)) if (!ids.contains(entry.id)) retained.add(entry);
        write(vault, retained);
    }

    private static File file(Vault vault) {
        return new File(vault.sidecarDir(), FILE_NAME);
    }

    private static void write(Vault vault, List<Entry> entries) throws Exception {
        File target = file(vault);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new java.io.IOException("Cannot create task trash directory");
        }
        JSONArray values = new JSONArray();
        for (Entry entry : entries) if (entry != null) values.put(entry.toJson());
        NoteFile.writeAtomic(target, values.toString());
    }
}
