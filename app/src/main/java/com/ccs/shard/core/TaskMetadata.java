package com.ccs.shard.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codec and calendar rules for the portable metadata stored beside Markdown
 * task checkboxes. This class deliberately has no repository or Android API.
 */
public final class TaskMetadata {

    private static final Pattern DUE = Pattern.compile(
            "(?:📅\\s*|@due\\s*\\()([0-9]{4}-[0-9]{2}-[0-9]{2})\\)?");
    private static final Pattern TIME = Pattern.compile(
            "(?:⏰\\s*|@time\\s*\\()([0-2][0-9]:[0-5][0-9])\\)?");
    private static final Pattern REMINDER = Pattern.compile(
            "(?:🔔\\s*|@remind\\s*\\()([0-2][0-9]:[0-5][0-9])\\)?");
    private static final Pattern PRIORITY = Pattern.compile(
            "(?:@priority\\s*\\()(?i:(low|medium|high))\\)?");
    private static final Pattern REPEAT = Pattern.compile(
            "(?:🔁\\s*|@repeat\\s*\\()(?i:(daily|weekdays|weekly|monthly))\\)?");
    private static final Pattern ARCHIVED = Pattern.compile("@archived\\b");

    private TaskMetadata() { }

    /** Parses metadata from either a task body or a complete Markdown task line. */
    public static TaskDetails parse(String source) {
        String raw = source == null ? "" : source;
        TaskDetails details = new TaskDetails(raw);
        Matcher due = DUE.matcher(raw);
        if (due.find()) details.dueMillis = parseDay(due.group(1));
        Matcher time = TIME.matcher(raw);
        if (time.find()) details.dueTimeMinutes = parseClock(time.group(1));
        Matcher reminder = REMINDER.matcher(raw);
        if (reminder.find()) details.reminderMinutes = parseClock(reminder.group(1));
        Matcher priority = PRIORITY.matcher(raw);
        if (priority.find()) details.priority = priorityOf(priority.group(1));
        Matcher repeat = REPEAT.matcher(raw);
        if (repeat.find()) details.repeat = repeatOf(repeat.group(1));
        details.archived = ARCHIVED.matcher(raw).find();
        details.cleanText = strip(source);
        return details;
    }

    /** Builds editable metadata from a scanned task. */
    public static TaskDetails from(TaskItem task) {
        TaskDetails details = new TaskDetails(task == null ? "" : task.text);
        if (task == null) return details;
        details.dueMillis = task.dueMillis;
        details.dueTimeMinutes = task.dueTimeMinutes;
        details.reminderMinutes = task.reminderMinutes;
        details.priority = task.priority;
        details.repeat = task.repeat;
        details.archived = task.archived;
        return details;
    }

    /** Replaces metadata while retaining the task wording and Markdown marker. */
    public static String write(String source, TaskDetails details) {
        if (details == null) return source == null ? "" : source;
        StringBuilder out = new StringBuilder(strip(source));
        if (details.priority != TaskItem.PRIORITY_NONE) {
            append(out, "@priority(" + priorityName(details.priority) + ")");
        }
        if (details.dueMillis > 0) append(out, "📅 " + formatDay(details.dueMillis));
        if (details.dueTimeMinutes >= 0) {
            append(out, "@time(" + formatClock(details.dueTimeMinutes) + ")");
        }
        if (details.reminderMinutes >= 0) {
            append(out, "@remind(" + formatClock(details.reminderMinutes) + ")");
        }
        if (details.repeat != null && details.repeat != TaskItem.Repeat.NONE) {
            append(out, "@repeat(" + repeatName(details.repeat) + ")");
        }
        if (details.archived) append(out, "@archived");
        return out.toString().trim();
    }

    /** Advances a recurring task to its next deadline without completing it. */
    public static String advanceRecurring(String source) {
        TaskDetails details = parse(source);
        if (details.repeat == TaskItem.Repeat.NONE) return source == null ? "" : source;
        details.dueMillis = nextOccurrence(details.dueMillis, details.repeat);
        return write(source, details);
    }

    public static boolean isRecurring(String source) {
        return parse(source).repeat != TaskItem.Repeat.NONE;
    }

    /** Returns a future day for a recurrence; overdue occurrences are caught up. */
    public static long nextOccurrence(long dueMillis, TaskItem.Repeat repeat) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dueMillis > 0 ? dueMillis : startOfToday());
        long today = startOfToday();
        do {
            switch (repeat == null ? TaskItem.Repeat.NONE : repeat) {
                case DAILY:
                    calendar.add(Calendar.DAY_OF_YEAR, 1);
                    break;
                case WEEKDAYS:
                    calendar.add(Calendar.DAY_OF_YEAR, 1);
                    while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
                            || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                        calendar.add(Calendar.DAY_OF_YEAR, 1);
                    }
                    break;
                case WEEKLY:
                    calendar.add(Calendar.WEEK_OF_YEAR, 1);
                    break;
                case MONTHLY:
                    calendar.add(Calendar.MONTH, 1);
                    break;
                case NONE:
                default:
                    calendar.add(Calendar.DAY_OF_YEAR, 1);
                    break;
            }
        } while (startOfDay(calendar.getTimeInMillis()) <= today);
        return startOfDay(calendar.getTimeInMillis());
    }

    public static long parseDay(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            format.setLenient(false);
            Date parsed = format.parse(value);
            return parsed == null ? 0 : startOfDay(parsed.getTime());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static long startOfToday() { return startOfDay(System.currentTimeMillis()); }

    public static long startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    static String withTag(String source, String value, boolean add) {
        String line = source == null ? "" : source;
        String tag = Md.normalizeTag(value);
        if (tag.isEmpty()) return line;

        String marker = "#" + tag;
        Pattern exactTag = Pattern.compile("(?<![\\p{L}\\p{N}_/])"
                + Pattern.quote(marker) + "(?![\\p{L}\\p{N}_/-])");
        if (add) {
            if (exactTag.matcher(line).find()) return line;
            return line.isEmpty() ? marker : line + " " + marker;
        }
        return exactTag.matcher(line).replaceAll("")
                .replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static String strip(String source) {
        String clean = source == null ? "" : source;
        clean = DUE.matcher(clean).replaceAll("");
        clean = TIME.matcher(clean).replaceAll("");
        clean = REMINDER.matcher(clean).replaceAll("");
        clean = PRIORITY.matcher(clean).replaceAll("");
        clean = REPEAT.matcher(clean).replaceAll("");
        clean = ARCHIVED.matcher(clean).replaceAll("");
        return clean.replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static int parseClock(String value) {
        if (value == null || value.length() != 5) return -1;
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(3, 5));
            return hour >= 0 && hour < 24 && minute >= 0 && minute < 60
                    ? hour * 60 + minute : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int priorityOf(String value) {
        if ("high".equalsIgnoreCase(value)) return TaskItem.PRIORITY_HIGH;
        if ("medium".equalsIgnoreCase(value)) return TaskItem.PRIORITY_MEDIUM;
        if ("low".equalsIgnoreCase(value)) return TaskItem.PRIORITY_LOW;
        return TaskItem.PRIORITY_NONE;
    }

    private static String priorityName(int priority) {
        switch (priority) {
            case TaskItem.PRIORITY_HIGH: return "high";
            case TaskItem.PRIORITY_MEDIUM: return "medium";
            case TaskItem.PRIORITY_LOW: return "low";
            default: return "";
        }
    }

    private static TaskItem.Repeat repeatOf(String value) {
        if ("daily".equalsIgnoreCase(value)) return TaskItem.Repeat.DAILY;
        if ("weekdays".equalsIgnoreCase(value)) return TaskItem.Repeat.WEEKDAYS;
        if ("weekly".equalsIgnoreCase(value)) return TaskItem.Repeat.WEEKLY;
        if ("monthly".equalsIgnoreCase(value)) return TaskItem.Repeat.MONTHLY;
        return TaskItem.Repeat.NONE;
    }

    private static String repeatName(TaskItem.Repeat repeat) {
        switch (repeat == null ? TaskItem.Repeat.NONE : repeat) {
            case DAILY: return "daily";
            case WEEKDAYS: return "weekdays";
            case WEEKLY: return "weekly";
            case MONTHLY: return "monthly";
            case NONE:
            default: return "";
        }
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isEmpty()) return;
        if (target.length() > 0 && !Character.isWhitespace(target.charAt(target.length() - 1))) {
            target.append(' ');
        }
        target.append(value);
    }

    private static String formatDay(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(millis));
    }

    private static String formatClock(int minutes) {
        int safe = Math.max(0, Math.min(23 * 60 + 59, minutes));
        return String.format(Locale.ROOT, "%02d:%02d", safe / 60, safe % 60);
    }
}
