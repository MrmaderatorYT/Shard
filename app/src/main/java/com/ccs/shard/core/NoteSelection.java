package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ordered, UI-agnostic selection state for note collections.
 *
 * <p>The model owns selection invariants while an Activity only maps its state
 * to toolbar visibility and adapter updates.
 */
public final class NoteSelection {

    private final Set<String> ids = new LinkedHashSet<>();
    private boolean active;

    public boolean isActive() { return active; }

    public boolean isEmpty() { return ids.isEmpty(); }

    public int size() { return ids.size(); }

    public boolean contains(String noteId) { return ids.contains(noteId); }

    /** Starts a fresh selection containing exactly one valid id. */
    public void start(String noteId) {
        active = true;
        ids.clear();
        if (noteId != null && !noteId.isEmpty()) ids.add(noteId);
    }

    /** Toggles one id and returns whether it is selected afterwards. */
    public boolean toggle(String noteId) {
        if (noteId == null || noteId.isEmpty()) return false;
        active = true;
        if (!ids.add(noteId)) ids.remove(noteId);
        if (ids.isEmpty()) active = false;
        return ids.contains(noteId);
    }

    /** Selects all provided ids, or clears those ids when they are all selected. */
    public void toggleAll(Collection<String> visibleIds) {
        if (!active || visibleIds == null || visibleIds.isEmpty()) return;
        boolean allSelected = ids.containsAll(visibleIds);
        if (allSelected) ids.removeAll(visibleIds);
        else {
            for (String id : visibleIds) {
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        if (ids.isEmpty()) active = false;
    }

    /** Drops ids which no longer belong to the active vault. */
    public void retainOnly(Collection<String> availableIds) {
        if (availableIds == null) ids.clear();
        else ids.retainAll(availableIds);
        if (ids.isEmpty()) active = false;
    }

    public List<String> ids() { return new ArrayList<>(ids); }

    public void clear() {
        active = false;
        ids.clear();
    }
}
