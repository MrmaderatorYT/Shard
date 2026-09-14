package com.ccs.shard.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class NoteSelectionTest {

    @Test
    public void togglingVisibleNotesMaintainsAStableSelectionAndExitsWhenEmpty() {
        NoteSelection selection = new NoteSelection();
        selection.start("one.md");

        selection.toggleAll(Arrays.asList("one.md", "two.md"));
        assertEquals(Arrays.asList("one.md", "two.md"), selection.ids());

        selection.toggleAll(Arrays.asList("one.md", "two.md"));
        assertFalse(selection.isActive());
        assertTrue(selection.isEmpty());
    }

    @Test
    public void retainOnlyPrunesDeletedNotesWithoutReorderingTheRest() {
        NoteSelection selection = new NoteSelection();
        selection.start("one.md");
        selection.toggle("two.md");
        selection.toggle("three.md");

        selection.retainOnly(Arrays.asList("three.md", "one.md"));

        assertEquals(Arrays.asList("one.md", "three.md"), selection.ids());
        assertTrue(selection.isActive());
    }
}
