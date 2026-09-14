package com.ccs.shard.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.ccs.shard.TaskReminderReceiver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scans portable Markdown tasks and schedules their optional due-date reminders. */
public final class TaskRepository {

    private static final Pattern TASK = Pattern.compile("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.*)$");
    private TaskRepository() {}

    /** Blocking; call on the disk executor. */
    public static List<TaskItem> scan(VaultRepository repository) {
        List<TaskItem> out = new ArrayList<>();
        for (Note meta : repository.notes()) {
            try {
                Note note = repository.loadNoteSync(meta.getId());
                String content = note.getContent() == null ? "" : note.getContent();
                String[] lines = content.split("\\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    Matcher task = TASK.matcher(lines[i]);
                    if (!task.matches()) continue;
                    String rawText = task.group(2).trim();
                    TaskDetails details = TaskMetadata.parse(rawText);
                    out.add(new TaskItem(note.getId(), note.getTitle(), details.cleanText, i,
                            !task.group(1).trim().isEmpty(), details.dueMillis,
                            details.dueTimeMinutes, details.reminderMinutes,
                            details.priority, details.repeat, details.archived));
                }
            } catch (Throwable ignored) {
                // One malformed or temporarily locked note must not hide other tasks.
            }
        }
        Collections.sort(out, new Comparator<TaskItem>() {
            @Override public int compare(TaskItem a, TaskItem b) {
                if (a.checked != b.checked) return a.checked ? 1 : -1;
                long ad = a.dueMillis == 0 ? Long.MAX_VALUE : a.dueMillis;
                long bd = b.dueMillis == 0 ? Long.MAX_VALUE : b.dueMillis;
                int due = Long.compare(ad, bd);
                if (due != 0) return due;
                int priority = Integer.compare(b.priority, a.priority);
                if (priority != 0) return priority;
                int at = a.dueTimeMinutes < 0 ? Integer.MAX_VALUE : a.dueTimeMinutes;
                int bt = b.dueTimeMinutes < 0 ? Integer.MAX_VALUE : b.dueTimeMinutes;
                int time = Integer.compare(at, bt);
                return time != 0 ? time : a.text.compareToIgnoreCase(b.text);
            }
        });
        return out;
    }

    public static void setChecked(VaultRepository repository, TaskItem task,
                                  boolean checked, Runnable complete) {
        final boolean requestedChecked = checked;
        Io.onDisk(() -> {
            try {
                Note note = repository.loadNoteSync(task.noteId);
                String content = note.getContent() == null ? "" : note.getContent();
                String[] lines = content.split("\\n", -1);
                if (task.lineIndex >= 0 && task.lineIndex < lines.length) {
                    String line = lines[task.lineIndex];
                    TaskDetails details = TaskMetadata.parse(line);
                    boolean finalChecked = requestedChecked;
                    if (finalChecked && details.repeat != TaskItem.Repeat.NONE) {
                        details.dueMillis = TaskMetadata.nextOccurrence(details.dueMillis,
                                details.repeat);
                        line = TaskMetadata.write(line, details);
                        finalChecked = false;
                    }
                    lines[task.lineIndex] = line.replaceFirst(
                            "\\[([ xX])]", finalChecked ? "[x]" : "[ ]");
                    StringBuilder updated = new StringBuilder(content.length());
                    for (int i = 0; i < lines.length; i++) {
                        if (i > 0) updated.append('\n');
                        updated.append(lines[i]);
                    }
                    note.setContent(updated.toString());
                    Io.onMain(() -> {
                        repository.save(note, false);
                        if (complete != null) complete.run();
                    });
                    return;
                }
            } catch (Throwable ignored) {}
            if (complete != null) Io.onMain(complete);
        });
    }

    /** Reopens a task on a different day, preserving all of its other metadata. */
    public static void reschedule(VaultRepository repository, TaskItem task, long dueMillis,
                                  Runnable complete) {
        TaskDetails details = TaskMetadata.from(task);
        details.dueMillis = TaskMetadata.startOfDay(dueMillis);
        updateDetails(repository, task, details, true, complete);
    }

    /** Rewrites only task metadata; task text and its list indentation stay unchanged. */
    public static void updateDetails(VaultRepository repository, TaskItem task,
                                     TaskDetails details, boolean reopen, Runnable complete) {
        if (task == null || details == null) {
            if (complete != null) complete.run();
            return;
        }
        Io.onDisk(() -> {
            try {
                Note note = repository.loadNoteSync(task.noteId);
                String content = note.getContent() == null ? "" : note.getContent();
                String[] lines = content.split("\\n", -1);
                if (task.lineIndex >= 0 && task.lineIndex < lines.length) {
                    String updatedLine = TaskMetadata.write(lines[task.lineIndex], details);
                    if (reopen) updatedLine = updatedLine.replaceFirst("\\[([ xX])]", "[ ]");
                    lines[task.lineIndex] = updatedLine;
                    saveLines(repository, note, content, lines, complete);
                    return;
                }
            } catch (Throwable ignored) { }
            if (complete != null) Io.onMain(complete);
        });
    }

    /** Changes one metadata field for every selected task without rewriting unrelated notes. */
    public interface DetailsMutation {
        void apply(TaskDetails details);
    }

    public static void bulkUpdateDetails(VaultRepository repository, List<TaskItem> tasks,
                                         DetailsMutation mutation, boolean reopen,
                                         Io.Result<Integer> callback) {
        final Map<String, List<TaskItem>> grouped = groupByNote(tasks);
        final AtomicInteger changedTasks = new AtomicInteger();
        repository.bulkEdit(new ArrayList<>(grouped.keySet()), note -> {
            List<TaskItem> inNote = grouped.get(note.getId());
            if (inNote == null || inNote.isEmpty()) return false;
            String content = note.getContent() == null ? "" : note.getContent();
            String[] lines = content.split("\\n", -1);
            boolean changed = false;
            for (TaskItem task : inNote) {
                if (task.lineIndex < 0 || task.lineIndex >= lines.length) continue;
                if (!TASK.matcher(lines[task.lineIndex]).matches()) continue;
                TaskDetails details = TaskMetadata.parse(lines[task.lineIndex]);
                if (mutation != null) mutation.apply(details);
                String updated = TaskMetadata.write(lines[task.lineIndex], details);
                if (reopen) updated = updated.replaceFirst("\\[([ xX])]", "[ ]");
                if (!updated.equals(lines[task.lineIndex])) {
                    lines[task.lineIndex] = updated;
                    changed = true;
                    changedTasks.incrementAndGet();
                }
            }
            if (changed) note.setContent(joinLines(lines));
            return changed;
        }, taskCountCallback(changedTasks, callback));
    }

    /** Adds or removes one inline tag only on selected task lines. */
    public static void bulkTag(VaultRepository repository, List<TaskItem> tasks, String tag,
                               boolean add, Io.Result<Integer> callback) {
        final Map<String, List<TaskItem>> grouped = groupByNote(tasks);
        final AtomicInteger changedTasks = new AtomicInteger();
        repository.bulkEdit(new ArrayList<>(grouped.keySet()), note -> {
            List<TaskItem> inNote = grouped.get(note.getId());
            if (inNote == null || inNote.isEmpty()) return false;
            String content = note.getContent() == null ? "" : note.getContent();
            String[] lines = content.split("\\n", -1);
            boolean changed = false;
            for (TaskItem task : inNote) {
                if (task.lineIndex < 0 || task.lineIndex >= lines.length) continue;
                if (!TASK.matcher(lines[task.lineIndex]).matches()) continue;
                String updated = TaskMetadata.withTag(lines[task.lineIndex], tag, add);
                if (!updated.equals(lines[task.lineIndex])) {
                    lines[task.lineIndex] = updated;
                    changed = true;
                    changedTasks.incrementAndGet();
                }
            }
            if (changed) note.setContent(joinLines(lines));
            return changed;
        }, taskCountCallback(changedTasks, callback));
    }

    /** Exports selected tasks as a standalone, portable Markdown checklist. */
    public static File exportTasks(VaultRepository repository, List<TaskItem> tasks)
            throws IOException {
        File file = new File(repository.vault().exportCacheDir(), "shard-tasks.md");
        StringBuilder out = new StringBuilder("# Tasks\n\n");
        String currentNote = null;
        if (tasks != null) {
            for (TaskItem task : tasks) {
                if (task == null) continue;
                if (!task.noteTitle.equals(currentNote)) {
                    if (currentNote != null) out.append('\n');
                    currentNote = task.noteTitle;
                    out.append("## ").append(currentNote).append("\n\n");
                }
                TaskDetails details = TaskMetadata.from(task);
                out.append(task.checked ? "- [x] " : "- [ ] ")
                        .append(TaskMetadata.write(task.text, details)).append('\n');
            }
        }
        NoteFile.writeAtomic(file, out.toString());
        return file;
    }

    /** Moves selected task lines to a recoverable task-only trash. */
    public static void bulkDelete(VaultRepository repository, List<TaskItem> tasks,
                                  Io.Result<Integer> callback) {
        final Map<String, List<TaskItem>> grouped = groupByNote(tasks);
        final List<TaskTrashStore.Entry> deleted = new ArrayList<>();
        repository.bulkEdit(new ArrayList<>(grouped.keySet()), note -> {
            List<TaskItem> inNote = grouped.get(note.getId());
            if (inNote == null || inNote.isEmpty()) return false;
            String content = note.getContent() == null ? "" : note.getContent();
            List<String> lines = new ArrayList<>();
            Collections.addAll(lines, content.split("\\n", -1));
            List<TaskItem> reverse = new ArrayList<>(inNote);
            Collections.sort(reverse, (a, b) -> Integer.compare(b.lineIndex, a.lineIndex));
            boolean changed = false;
            for (TaskItem task : reverse) {
                if (task.lineIndex < 0 || task.lineIndex >= lines.size()) continue;
                String line = lines.get(task.lineIndex);
                if (!TASK.matcher(line).matches()) continue;
                deleted.add(TaskTrashStore.Entry.of(task.noteId, task.noteTitle,
                        task.lineIndex, line));
                lines.remove(task.lineIndex);
                changed = true;
            }
            if (changed) note.setContent(joinLines(lines));
            return changed;
        }, new Io.Result<Integer>() {
            @Override public void onReady(Integer count) {
                if (deleted.isEmpty()) {
                    if (callback != null) callback.onReady(0);
                    return;
                }
                Io.load(() -> {
                    TaskTrashStore.append(repository.vault(), deleted);
                    return deleted.size();
                }, callback == null ? new Io.Ok<Integer>() {
                    @Override public void onReady(Integer ignored) { }
                } : callback);
            }

            @Override public void onError(Throwable error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    /** Restores selected task-trash entries at their original position when the note still exists. */
    public static void restoreDeleted(VaultRepository repository,
                                      List<TaskTrashStore.Entry> entries,
                                      Io.Result<Integer> callback) {
        final Map<String, List<TaskTrashStore.Entry>> grouped = new LinkedHashMap<>();
        if (entries != null) {
            for (TaskTrashStore.Entry entry : entries) {
                if (entry == null || repository.meta(entry.noteId) == null) continue;
                List<TaskTrashStore.Entry> list = grouped.get(entry.noteId);
                if (list == null) {
                    list = new ArrayList<>();
                    grouped.put(entry.noteId, list);
                }
                list.add(entry);
            }
        }
        final List<TaskTrashStore.Entry> restoring = new ArrayList<>();
        for (List<TaskTrashStore.Entry> list : grouped.values()) restoring.addAll(list);
        repository.bulkEdit(new ArrayList<>(grouped.keySet()), note -> {
            List<TaskTrashStore.Entry> inNote = grouped.get(note.getId());
            if (inNote == null || inNote.isEmpty()) return false;
            String content = note.getContent() == null ? "" : note.getContent();
            List<String> lines = new ArrayList<>();
            Collections.addAll(lines, content.split("\\n", -1));
            Collections.sort(inNote, (a, b) -> Integer.compare(a.lineIndex, b.lineIndex));
            for (TaskTrashStore.Entry entry : inNote) {
                lines.add(Math.max(0, Math.min(entry.lineIndex, lines.size())), entry.line);
            }
            note.setContent(joinLines(lines));
            return true;
        }, new Io.Result<Integer>() {
            @Override public void onReady(Integer changedNotes) {
                if (restoring.isEmpty() || changedNotes == null || changedNotes == 0) {
                    if (callback != null) callback.onReady(0);
                    return;
                }
                Io.load(() -> {
                    TaskTrashStore.remove(repository.vault(), restoring);
                    return restoring.size();
                }, callback == null ? new Io.Ok<Integer>() {
                    @Override public void onReady(Integer ignored) { }
                } : callback);
            }

            @Override public void onError(Throwable error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    /** Schedules one notification per future, unfinished dated task. */
    public static void scheduleReminders(Context context, List<TaskItem> tasks) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long now = System.currentTimeMillis();
        for (TaskItem task : tasks) {
            if (task.archived || task.checked || task.dueMillis <= 0) continue;
            Calendar time = Calendar.getInstance();
            time.setTimeInMillis(task.dueMillis);
            int reminder = task.reminderMinutes >= 0 ? task.reminderMinutes
                    : (task.dueTimeMinutes >= 0 ? task.dueTimeMinutes : 9 * 60);
            time.set(Calendar.HOUR_OF_DAY, reminder / 60);
            time.set(Calendar.MINUTE, reminder % 60);
            time.set(Calendar.SECOND, 0);
            time.set(Calendar.MILLISECOND, 0);
            long trigger = time.getTimeInMillis();
            if (trigger <= now) continue;
            Intent intent = new Intent(context, TaskReminderReceiver.class)
                    .putExtra(TaskReminderReceiver.EXTRA_NOTE_ID, task.noteId)
                    .putExtra(TaskReminderReceiver.EXTRA_TEXT, task.text)
                    .putExtra(TaskReminderReceiver.EXTRA_TASK_ID, task.stableId());
            PendingIntent pending = PendingIntent.getBroadcast(context, task.stableId(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, trigger, pending);
            }
        }
    }

    private static void saveLines(VaultRepository repository, Note note, String original,
                                  String[] lines, Runnable complete) {
        note.setContent(joinLines(lines));
        Io.onMain(() -> {
            repository.save(note, false);
            if (complete != null) complete.run();
        });
    }

    /** VaultRepository reports changed notes; the task UI needs the changed task count. */
    private static Io.Result<Integer> taskCountCallback(final AtomicInteger changedTasks,
                                                         final Io.Result<Integer> callback) {
        return new Io.Result<Integer>() {
            @Override public void onReady(Integer ignored) {
                if (callback != null) callback.onReady(changedTasks.get());
            }

            @Override public void onError(Throwable error) {
                if (callback != null) callback.onError(error);
            }
        };
    }

    private static Map<String, List<TaskItem>> groupByNote(List<TaskItem> tasks) {
        Map<String, List<TaskItem>> grouped = new LinkedHashMap<>();
        if (tasks == null) return grouped;
        for (TaskItem task : tasks) {
            if (task == null || task.noteId == null) continue;
            List<TaskItem> inNote = grouped.get(task.noteId);
            if (inNote == null) {
                inNote = new ArrayList<>();
                grouped.put(task.noteId, inNote);
            }
            inNote.add(task);
        }
        return grouped;
    }

    private static String joinLines(String[] lines) {
        StringBuilder out = new StringBuilder();
        if (lines == null) return "";
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        if (lines == null) return "";
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(lines.get(i));
        }
        return out.toString();
    }
}
