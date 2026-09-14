package com.ccs.shard.core;

import java.util.List;

/**
 * Persisted navigation trail for notes.
 *
 * <p>The interface keeps UI navigation independent of {@link Prefs}. A future
 * implementation can keep the trail in a database or restore it from a saved
 * session without changing editor code.
 */
public interface NoteNavigationHistory {

    /** A chronological snapshot of note ids. */
    List<String> navigationEntries();

    /** Current position in {@link #navigationEntries()}, or {@code -1} when empty. */
    int navigationPosition();

    /** Moves relatively through the trail, returning the selected note id. */
    String navigateNote(int delta);

    /** Selects a known trail position, returning the selected note id. */
    String navigateToHistoryIndex(int index);
}
