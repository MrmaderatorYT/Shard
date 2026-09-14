package com.ccs.shard.block;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo/redo for the whole note.
 *
 * <p>Snapshots are of the serialised Markdown rather than per-keystroke text
 * diffs. That is coarser than a character-level stack, but it is the only
 * approach that stays correct across structural edits — splitting a block,
 * deleting a table row, converting a paragraph to a heading — and it costs a few
 * kilobytes per step for a note of realistic length.
 *
 * <p>Snapshots are coalesced: a run of typing inside {@link #COALESCE_MS}
 * collapses into one undo step, so Undo moves in units a person recognises.
 */
public final class DocHistory {

    private static final int MAX_STEPS = 60;
    private static final long COALESCE_MS = 900L;
    /** Skip history entirely for very large notes to bound memory. */
    private static final int MAX_SNAPSHOT_CHARS = 400_000;

    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();

    private String current;
    private long lastPushAt;
    private boolean coalescing;

    public interface Listener {
        void onHistoryChanged(boolean canUndo, boolean canRedo);
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
        notifyChanged();
    }

    /** Sets the baseline without creating an undo step. */
    public void reset(String markdown) {
        undoStack.clear();
        redoStack.clear();
        current = markdown;
        lastPushAt = 0;
        coalescing = false;
        notifyChanged();
    }

    /**
     * Records a new document state.
     *
     * @param nowMillis caller-supplied clock, so tests stay deterministic
     */
    public void record(String markdown, long nowMillis) {
        if (markdown == null || markdown.equals(current)) return;
        if (markdown.length() > MAX_SNAPSHOT_CHARS) {
            current = markdown;
            return;
        }
        boolean withinRun = coalescing && (nowMillis - lastPushAt) < COALESCE_MS;
        if (!withinRun && current != null) {
            undoStack.push(current);
            while (undoStack.size() > MAX_STEPS) undoStack.removeLast();
            redoStack.clear();
        }
        current = markdown;
        lastPushAt = nowMillis;
        coalescing = true;
        notifyChanged();
    }

    /** Ends the current coalescing run, so the next edit starts a new undo step. */
    public void breakRun() {
        coalescing = false;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }

    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** @return the document to restore, or null when there is nothing to undo */
    public String undo() {
        if (undoStack.isEmpty()) return null;
        if (current != null) redoStack.push(current);
        current = undoStack.pop();
        coalescing = false;
        notifyChanged();
        return current;
    }

    public String redo() {
        if (redoStack.isEmpty()) return null;
        if (current != null) undoStack.push(current);
        current = redoStack.pop();
        coalescing = false;
        notifyChanged();
        return current;
    }

    public String currentSnapshot() { return current; }

    private void notifyChanged() {
        if (listener != null) listener.onHistoryChanged(canUndo(), canRedo());
    }
}
