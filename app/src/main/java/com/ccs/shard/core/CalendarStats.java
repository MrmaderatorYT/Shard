package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the month grid and its note/task activity from indexed vault data. */
public final class CalendarStats {

    private CalendarStats() {}

    /** One calendar cell; the first and last cells can belong to adjacent months. */
    public static final class Day {
        public final long millis;
        public final int year;
        public final int month;
        public final int dayOfMonth;
        public final boolean inMonth;
        /** Notes created on this day. */
        public int noteCount;
        /** Tasks due on this day, or undated tasks inside that day's daily note. */
        public int taskCount;
        /** Unchecked tasks from {@link #taskCount}. */
        public int openTaskCount;
        /** Notes whose file was modified on this day. */
        public int activityCount;

        private Day(Calendar date, int year, int month) {
            millis = date.getTimeInMillis();
            this.year = date.get(Calendar.YEAR);
            this.month = date.get(Calendar.MONTH);
            dayOfMonth = date.get(Calendar.DAY_OF_MONTH);
            inMonth = this.year == year && this.month == month;
        }

        public boolean hasNotes() { return noteCount > 0; }

        public boolean hasTasks() { return taskCount > 0; }

        public boolean hasOpenTasks() { return openTaskCount > 0; }
    }

    /** Returns a Monday-first, six-week grid for the zero-based calendar month. */
    public static List<Day> build(int year, int month, List<Note> notes,
                                  List<TaskItem> tasks) {
        Calendar first = Calendar.getInstance();
        first.clear();
        first.set(year, month, 1, 0, 0, 0);
        first.set(Calendar.MILLISECOND, 0);
        while (first.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            first.add(Calendar.DAY_OF_MONTH, -1);
        }

        List<Day> grid = new ArrayList<>(42);
        Map<String, Day> byDate = new HashMap<>();
        Calendar cursor = (Calendar) first.clone();
        for (int i = 0; i < 42; i++) {
            Day day = new Day(cursor, year, month);
            grid.add(day);
            byDate.put(key(day.millis), day);
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }

        if (notes != null) {
            for (Note note : notes) {
                if (note == null) continue;
                Day created = byDate.get(key(note.getCreatedMillis() > 0
                        ? note.getCreatedMillis() : note.getModifiedMillis()));
                if (created != null && created.inMonth) created.noteCount++;

                Day modified = byDate.get(key(note.getModifiedMillis()));
                if (modified != null && modified.inMonth) modified.activityCount++;
            }
        }

        if (tasks != null) {
            for (TaskItem task : tasks) {
                if (task == null || task.archived) continue;
                long dayMillis = task.dueMillis > 0
                        ? task.dueMillis : dailyNoteDay(task.noteId);
                if (dayMillis <= 0) continue;
                Day day = byDate.get(key(dayMillis));
                if (day == null || !day.inMonth) continue;
                day.taskCount++;
                if (!task.checked) day.openTaskCount++;
            }
        }
        return grid;
    }

    /** Stable local-day key used to avoid DST/time-of-day mismatches. */
    public static String key(long millis) {
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(millis);
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH));
    }

    private static long dailyNoteDay(String noteId) {
        if (noteId == null) return 0L;
        String prefix = DailyNotes.FOLDER + "/";
        if (!noteId.startsWith(prefix)) return 0L;
        String name = noteId.substring(prefix.length());
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return TaskMetadata.parseDay(name);
    }
}
