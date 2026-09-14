package com.ccs.shard.util;

import com.ccs.shard.core.Note;
import com.ccs.shard.core.Prefs;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Orders notes for the browser.
 *
 * <p>Pinned notes always float to the top regardless of the chosen sort — a pin
 * that stops working when you change the sort order is not a pin.
 */
public final class NoteSorter {

    private NoteSorter() {}

    public static void sort(List<Note> notes, int mode, boolean descending) {
        final Comparator<Note> base = comparatorFor(mode);
        final int direction = descending ? -1 : 1;
        Collections.sort(notes, new Comparator<Note>() {
            @Override public int compare(Note a, Note b) {
                if (a.isPinned() != b.isPinned()) return a.isPinned() ? -1 : 1;
                return direction * base.compare(a, b);
            }
        });
    }

    private static Comparator<Note> comparatorFor(int mode) {
        switch (mode) {
            case Prefs.SORT_CREATED:
                return new Comparator<Note>() {
                    @Override public int compare(Note a, Note b) {
                        return Long.compare(a.getCreatedMillis(), b.getCreatedMillis());
                    }
                };
            case Prefs.SORT_TITLE:
                return new Comparator<Note>() {
                    @Override public int compare(Note a, Note b) {
                        String left = a.getTitle() == null ? "" : a.getTitle();
                        String right = b.getTitle() == null ? "" : b.getTitle();
                        // Reversed so "descending" reads as A→Z for a title sort,
                        // which is what people expect from an alphabetical list.
                        return right.compareToIgnoreCase(left);
                    }
                };
            case Prefs.SORT_SIZE:
                return new Comparator<Note>() {
                    @Override public int compare(Note a, Note b) {
                        return Long.compare(a.getSizeBytes(), b.getSizeBytes());
                    }
                };
            case Prefs.SORT_MODIFIED:
            default:
                return new Comparator<Note>() {
                    @Override public int compare(Note a, Note b) {
                        return Long.compare(a.getModifiedMillis(), b.getModifiedMillis());
                    }
                };
        }
    }

    /** A stable colour derived from the title, used when a note has no explicit one. */
    public static int hueFor(Note note, int[] palette) {
        if (note.getColor() != 0) return note.getColor();
        String key = note.getId() == null ? "" : note.getId();
        int hash = 0;
        for (int i = 0; i < key.length(); i++) hash = hash * 31 + key.charAt(i);
        return palette[Math.abs(hash) % palette.length];
    }
}
