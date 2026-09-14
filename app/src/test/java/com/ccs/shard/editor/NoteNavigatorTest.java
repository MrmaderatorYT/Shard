package com.ccs.shard.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ccs.shard.block.BlockDocument;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteNavigationHistory;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for navigation rules that must not require an Android Activity. */
public final class NoteNavigatorTest {

    @Test
    public void history_skipsMissingNotesAndKeepsTheirOriginalSelectionIndex() {
        FakeHistory history = new FakeHistory("a.md", "missing.md", "b.md");
        history.position = 0;
        Map<String, Note> notes = new HashMap<>();
        notes.put("a.md", note("a.md", "Alpha"));
        notes.put("b.md", note("b.md", "Beta"));

        NoteNavigator navigator = new NoteNavigator(history, notes::get);
        List<NoteNavigator.HistoryEntry> entries = navigator.openedNotes();

        assertEquals(2, entries.size());
        assertEquals("b.md", entries.get(0).id());
        assertEquals(2, entries.get(0).index());
        assertEquals("a.md", entries.get(1).id());
        assertTrue(entries.get(1).isCurrent());
        assertEquals("b.md", navigator.selectHistory(entries.get(0).index()));
        assertEquals(2, history.position);
    }

    @Test
    public void breadcrumbs_includeRootAndEveryFolderAncestor() {
        NoteNavigator navigator = new NoteNavigator(new FakeHistory(), id -> null);

        List<NoteNavigator.Breadcrumb> crumbs =
                navigator.breadcrumbs("Work/Clients/Shard");

        assertEquals(4, crumbs.size());
        assertEquals("", crumbs.get(0).path());
        assertEquals("Work", crumbs.get(1).path());
        assertEquals("Work/Clients", crumbs.get(2).path());
        assertEquals("Work/Clients/Shard", crumbs.get(3).path());
        assertTrue(crumbs.get(3).isCurrent());
        assertFalse(crumbs.get(0).isCurrent());
    }

    @Test
    public void headings_arePlainTextWithTheirOriginalBlockPositions() {
        NoteNavigator navigator = new NoteNavigator(new FakeHistory(), id -> null);
        BlockDocument document = BlockDocument.parse("# **Plan**\n\nText\n\n### [[Details|Scope]]");

        List<NoteNavigator.Heading> headings = navigator.headings(document);

        assertEquals(2, headings.size());
        assertEquals(0, headings.get(0).blockIndex());
        assertEquals("Plan", headings.get(0).title());
        assertEquals(2, headings.get(1).blockIndex());
        assertEquals("Scope", headings.get(1).title());
    }

    private static Note note(String id, String title) {
        return new Note(id, title);
    }

    private static final class FakeHistory implements NoteNavigationHistory {
        private final List<String> entries;
        int position;

        FakeHistory(String... entries) {
            this.entries = Arrays.asList(entries);
            position = this.entries.isEmpty() ? -1 : this.entries.size() - 1;
        }

        @Override public List<String> navigationEntries() { return entries; }

        @Override public int navigationPosition() { return position; }

        @Override public String navigateNote(int delta) {
            int target = position + delta;
            if (target < 0 || target >= entries.size()) return null;
            position = target;
            return entries.get(target);
        }

        @Override public String navigateToHistoryIndex(int index) {
            if (index < 0 || index >= entries.size()) return null;
            position = index;
            return entries.get(index);
        }
    }
}
