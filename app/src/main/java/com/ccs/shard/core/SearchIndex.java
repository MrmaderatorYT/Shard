package com.ccs.shard.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Persistent full-text body index; searching never reopens Markdown files. */
final class SearchIndex extends SQLiteOpenHelper {

    private static final String TAG = "ShardSearch";
    private static final String DB_NAME = "note-search.db";
    private static final int DB_VERSION = 1;

    static final class Match {
        final String noteId;
        final String snippet;

        Match(String noteId, String snippet) {
            this.noteId = noteId;
            this.snippet = snippet;
        }
    }

    SearchIndex(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE docs (id TEXT PRIMARY KEY, modified INTEGER NOT NULL, "
                + "size INTEGER NOT NULL, body TEXT NOT NULL)");
        db.execSQL("CREATE VIRTUAL TABLE note_fts USING fts4(id, body, tokenize=unicode61)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS note_fts");
        db.execSQL("DROP TABLE IF EXISTS docs");
        onCreate(db);
    }

    boolean isCurrent(Note note) {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query("docs", new String[]{"modified", "size"},
                    "id=?", new String[]{note.getId()}, null, null, null, "1");
            return cursor.moveToFirst()
                    && cursor.getLong(0) == note.getModifiedMillis()
                    && cursor.getLong(1) == note.getSizeBytes();
        } catch (Throwable error) {
            Log.w(TAG, "cannot inspect search index", error);
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    void upsert(Note note) {
        if (note == null || note.getId() == null || !note.isLoaded()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("note_fts", "id=?", new String[]{note.getId()});
            db.delete("docs", "id=?", new String[]{note.getId()});
            ContentValues doc = new ContentValues();
            doc.put("id", note.getId());
            doc.put("modified", note.getModifiedMillis());
            doc.put("size", note.getSizeBytes());
            doc.put("body", note.getContent() == null ? "" : note.getContent());
            db.insertOrThrow("docs", null, doc);
            ContentValues fts = new ContentValues();
            fts.put("id", note.getId());
            fts.put("body", note.getContent() == null ? "" : note.getContent());
            db.insertOrThrow("note_fts", null, fts);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void delete(String id) {
        if (id == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete("note_fts", "id=?", new String[]{id});
        db.delete("docs", "id=?", new String[]{id});
    }

    void reid(String oldId, String newId) {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getWritableDatabase();
            cursor = db.query("docs", new String[]{"modified", "size", "body"}, "id=?",
                    new String[]{oldId}, null, null, null, "1");
            if (!cursor.moveToFirst()) return;
            long modified = cursor.getLong(0);
            long size = cursor.getLong(1);
            String body = cursor.getString(2);
            delete(oldId);
            ContentValues doc = new ContentValues();
            doc.put("id", newId);
            doc.put("modified", modified);
            doc.put("size", size);
            doc.put("body", body);
            db.insertOrThrow("docs", null, doc);
            ContentValues fts = new ContentValues();
            fts.put("id", newId);
            fts.put("body", body);
            db.insertOrThrow("note_fts", null, fts);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    List<Match> search(String query, int limit) {
        List<Match> out = new ArrayList<>();
        String match = matchExpression(query);
        if (!match.isEmpty()) {
            Cursor cursor = null;
            try {
                cursor = getReadableDatabase().rawQuery(
                        "SELECT id, snippet(note_fts, 1, '', '', ' … ', 18) "
                                + "FROM note_fts WHERE note_fts MATCH ? LIMIT ?",
                        new String[]{match, String.valueOf(limit)});
                while (cursor.moveToNext()) out.add(new Match(cursor.getString(0), cursor.getString(1)));
                if (!out.isEmpty()) return out;
            } catch (Throwable error) {
                Log.w(TAG, "FTS query failed; using bounded LIKE fallback", error);
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query("docs", new String[]{"id", "body"},
                    "body LIKE ?", new String[]{"%" + query + "%"}, null, null,
                    "modified DESC", String.valueOf(limit));
            String needle = query.toLowerCase(Locale.ROOT);
            while (cursor.moveToNext()) {
                String body = cursor.getString(1);
                int at = body.toLowerCase(Locale.ROOT).indexOf(needle);
                out.add(new Match(cursor.getString(0), snippet(body, Math.max(0, at), query.length())));
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
    }

    void prune(Set<String> currentIds) {
        Cursor cursor = null;
        List<String> stale = new ArrayList<>();
        try {
            cursor = getReadableDatabase().query("docs", new String[]{"id"},
                    null, null, null, null, null);
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (!currentIds.contains(id)) stale.add(id);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        for (String id : stale) delete(id);
    }

    void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("note_fts", null, null);
        db.delete("docs", null, null);
    }

    private static String matchExpression(String raw) {
        if (raw == null) return "";
        String[] words = raw.trim().split("[^\\p{L}\\p{N}_]+", -1);
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append('"').append(word.replace("\"", "\"\"")).append("\"*");
        }
        return out.toString();
    }

    private static String snippet(String body, int at, int length) {
        int start = Math.max(0, at - 70);
        int end = Math.min(body.length(), at + length + 100);
        return Md.plainText(body.substring(start, end), 190);
    }
}
