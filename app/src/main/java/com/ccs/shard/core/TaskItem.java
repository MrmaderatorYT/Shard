package com.ccs.shard.core;

/** One Markdown checkbox discovered inside a note. */
public final class TaskItem {
    public static final int PRIORITY_NONE = 0;
    public static final int PRIORITY_LOW = 1;
    public static final int PRIORITY_MEDIUM = 2;
    public static final int PRIORITY_HIGH = 3;

    /** How a task creates its next occurrence after it is completed. */
    public enum Repeat { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY }

    public final String noteId;
    public final String noteTitle;
    public final String text;
    public final int lineIndex;
    public final boolean checked;
    /** Start of the due day in local time, or 0 for an undated task. */
    public final long dueMillis;
    /** Minutes after midnight, or {@code -1} when the task has no execution time. */
    public final int dueTimeMinutes;
    /** Minutes after midnight for a notification, or {@code -1} for the default time. */
    public final int reminderMinutes;
    /** User-assigned importance. */
    public final int priority;
    /** Optional recurrence rule. */
    public final Repeat repeat;
    /** Hidden from active task groups but retained in the source note. */
    public final boolean archived;

    public TaskItem(String noteId, String noteTitle, String text, int lineIndex,
                    boolean checked, long dueMillis) {
        this(noteId, noteTitle, text, lineIndex, checked, dueMillis, -1, -1,
                PRIORITY_NONE, Repeat.NONE, false);
    }

    public TaskItem(String noteId, String noteTitle, String text, int lineIndex,
                    boolean checked, long dueMillis, int dueTimeMinutes,
                    int reminderMinutes, int priority, Repeat repeat) {
        this(noteId, noteTitle, text, lineIndex, checked, dueMillis, dueTimeMinutes,
                reminderMinutes, priority, repeat, false);
    }

    public TaskItem(String noteId, String noteTitle, String text, int lineIndex,
                    boolean checked, long dueMillis, int dueTimeMinutes,
                    int reminderMinutes, int priority, Repeat repeat, boolean archived) {
        this.noteId = noteId;
        this.noteTitle = noteTitle;
        this.text = text;
        this.lineIndex = lineIndex;
        this.checked = checked;
        this.dueMillis = dueMillis;
        this.dueTimeMinutes = dueTimeMinutes;
        this.reminderMinutes = reminderMinutes;
        this.priority = priority;
        this.repeat = repeat == null ? Repeat.NONE : repeat;
        this.archived = archived;
    }

    public int stableId() {
        return (noteId + ":" + lineIndex).hashCode();
    }
}
