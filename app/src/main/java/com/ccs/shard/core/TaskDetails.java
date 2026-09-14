package com.ccs.shard.core;

/**
 * Mutable, Android-free metadata attached to a Markdown task line.
 *
 * <p>The object is intentionally a small POJO: editors can populate it, the
 * metadata codec can serialise it, and the repository can persist it without
 * any layer depending on another layer's implementation details.
 */
public final class TaskDetails {
    public String cleanText;
    public long dueMillis;
    public int dueTimeMinutes = -1;
    public int reminderMinutes = -1;
    public int priority = TaskItem.PRIORITY_NONE;
    public TaskItem.Repeat repeat = TaskItem.Repeat.NONE;
    public boolean archived;

    public TaskDetails(String cleanText) {
        this.cleanText = cleanText == null ? "" : cleanText;
    }

    public TaskDetails copy() {
        TaskDetails copy = new TaskDetails(cleanText);
        copy.dueMillis = dueMillis;
        copy.dueTimeMinutes = dueTimeMinutes;
        copy.reminderMinutes = reminderMinutes;
        copy.priority = priority;
        copy.repeat = repeat;
        copy.archived = archived;
        return copy;
    }
}
