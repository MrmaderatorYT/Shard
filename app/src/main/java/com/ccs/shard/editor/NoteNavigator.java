package com.ccs.shard.editor;

import com.ccs.shard.block.Block;
import com.ccs.shard.block.BlockDocument;
import com.ccs.shard.block.BlockType;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteNavigationHistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android-free navigation use cases for a note editor.
 *
 * <p>It converts persistence and document data into small immutable view
 * models. Activities decide how to present those models (a popup, a drawer, a
 * keyboard shortcut), while navigation rules stay independently testable.
 */
public final class NoteNavigator {

    /** Lookup boundary: the navigator does not own a repository or Android context. */
    public interface NoteLookup {
        Note find(String noteId);
    }

    /** One usable item in the chronological note trail. */
    public static final class HistoryEntry {
        private final int index;
        private final String id;
        private final String title;
        private final String emoji;
        private final String folder;
        private final boolean current;

        HistoryEntry(int index, Note note, boolean current) {
            this.index = index;
            this.id = note.getId();
            this.title = note.getTitle() == null ? "" : note.getTitle();
            this.emoji = note.getEmoji();
            this.folder = note.folder();
            this.current = current;
        }

        public int index() { return index; }
        public String id() { return id; }
        public String title() { return title; }
        public String emoji() { return emoji; }
        public String folder() { return folder; }
        public boolean isCurrent() { return current; }
    }

    /** A folder segment the UI may present as a breadcrumb. */
    public static final class Breadcrumb {
        private final String path;
        private final boolean current;

        Breadcrumb(String path, boolean current) {
            this.path = path;
            this.current = current;
        }

        public String path() { return path; }
        public boolean isCurrent() { return current; }
    }

    /** One document heading and its source block position. */
    public static final class Heading {
        private final int blockIndex;
        private final BlockType type;
        private final String title;

        Heading(int blockIndex, BlockType type, String title) {
            this.blockIndex = blockIndex;
            this.type = type;
            this.title = title;
        }

        public int blockIndex() { return blockIndex; }
        public BlockType type() { return type; }
        public String title() { return title; }
    }

    private final NoteNavigationHistory history;
    private final NoteLookup notes;

    public NoteNavigator(NoteNavigationHistory history, NoteLookup notes) {
        if (history == null) throw new IllegalArgumentException("history == null");
        if (notes == null) throw new IllegalArgumentException("notes == null");
        this.history = history;
        this.notes = notes;
    }

    public String moveBy(int delta) {
        return history.navigateNote(delta);
    }

    public String selectHistory(int index) {
        return history.navigateToHistoryIndex(index);
    }

    /** Newest first; missing notes are omitted without rewriting persisted history. */
    public List<HistoryEntry> openedNotes() {
        List<String> ids = history.navigationEntries();
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        int current = history.navigationPosition();
        List<HistoryEntry> out = new ArrayList<>(ids.size());
        for (int i = ids.size() - 1; i >= 0; i--) {
            Note note = notes.find(ids.get(i));
            if (note != null) out.add(new HistoryEntry(i, note, i == current));
        }
        return out;
    }

    /** Root plus every non-empty ancestor of a vault-relative folder. */
    public List<Breadcrumb> breadcrumbs(String folder) {
        String current = folder == null ? "" : folder;
        List<Breadcrumb> out = new ArrayList<>();
        out.add(new Breadcrumb("", current.isEmpty()));
        String path = "";
        for (String segment : current.split("/")) {
            if (segment.isEmpty()) continue;
            path = path.isEmpty() ? segment : path + "/" + segment;
            out.add(new Breadcrumb(path, path.equals(current)));
        }
        return out;
    }

    /** Extracts non-empty H1–H3 blocks without coupling navigation to a view. */
    public List<Heading> headings(BlockDocument document) {
        if (document == null || document.size() == 0) return Collections.emptyList();
        List<Heading> out = new ArrayList<>();
        for (int i = 0; i < document.size(); i++) {
            Block block = document.get(i);
            if (block == null || !block.type.isHeading()) continue;
            String title = Md.stripInline(block.text).replace('\n', ' ').trim();
            if (!title.isEmpty()) out.add(new Heading(i, block.type, title));
        }
        return out;
    }
}
