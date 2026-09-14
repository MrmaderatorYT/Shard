package com.ccs.shard.ui;

import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteIndex;

/**
 * One row in the vault browser: a section label, a folder, or a note.
 *
 * <p>A single flat list of these keeps the browser to one adapter and one
 * scrolling container, which is what lets pinned notes, folders and search
 * results share the same list without special cases in the layout.
 */
public final class VaultItem {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_FOLDER = 1;
    public static final int TYPE_NOTE = 2;

    public final int type;
    public final CharSequence label;
    public final Note note;
    public final NoteIndex.FolderEntry folder;
    /** Extra context for a search hit, e.g. the matching line. */
    public final CharSequence snippet;

    private VaultItem(int type, CharSequence label, Note note,
                      NoteIndex.FolderEntry folder, CharSequence snippet) {
        this.type = type;
        this.label = label;
        this.note = note;
        this.folder = folder;
        this.snippet = snippet;
    }

    public static VaultItem header(CharSequence label) {
        return new VaultItem(TYPE_HEADER, label, null, null, null);
    }

    public static VaultItem folder(NoteIndex.FolderEntry entry) {
        return new VaultItem(TYPE_FOLDER, entry.name, null, entry, null);
    }

    public static VaultItem note(Note note) {
        return new VaultItem(TYPE_NOTE, note.getTitle(), note, null, null);
    }

    public static VaultItem note(Note note, CharSequence snippet) {
        return new VaultItem(TYPE_NOTE, note.getTitle(), note, null, snippet);
    }

    /** Stable id for RecyclerView animations. */
    public long id() {
        switch (type) {
            case TYPE_FOLDER: return ("f:" + folder.path).hashCode();
            case TYPE_NOTE: return ("n:" + note.getId()).hashCode();
            default: return ("h:" + label).hashCode();
        }
    }
}
