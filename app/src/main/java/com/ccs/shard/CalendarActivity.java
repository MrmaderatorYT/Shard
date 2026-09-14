package com.ccs.shard;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.CalendarStats;
import com.ccs.shard.core.DailyNotes;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.TaskItem;
import com.ccs.shard.core.TaskMetadata;
import com.ccs.shard.core.TaskRepository;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.ui.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Monthly view of daily notes, task deadlines and vault editing activity. */
public final class CalendarActivity extends BaseActivity
        implements VaultRepository.Listener {

    private GridLayout weekdays;
    private GridLayout grid;
    private TextView monthTitle;
    private TextView legend;
    private TextView summary;
    private TextView openDailyNote;
    private LinearLayout taskRows;
    private View progress;

    private final Calendar shownMonth = Calendar.getInstance();
    private long selectedDayMillis;
    private List<CalendarStats.Day> days = new ArrayList<>();
    private List<TaskItem> tasks = new ArrayList<>();
    private int requestToken;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_calendar);

        shownMonth.set(Calendar.DAY_OF_MONTH, 1);
        selectedDayMillis = TaskMetadata.startOfToday();
        weekdays = findViewById(R.id.calendarWeekdays);
        grid = findViewById(R.id.calendarGrid);
        monthTitle = findViewById(R.id.calendarMonth);
        legend = findViewById(R.id.calendarLegend);
        summary = findViewById(R.id.calendarSummary);
        openDailyNote = findViewById(R.id.btnOpenDailyNote);
        taskRows = findViewById(R.id.calendarTaskRows);
        progress = findViewById(R.id.calendarProgress);

        findViewById(R.id.btnCalendarBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCalendarPrevious).setOnClickListener(v -> changeMonth(-1));
        findViewById(R.id.btnCalendarNext).setOnClickListener(v -> changeMonth(1));
        findViewById(R.id.btnCalendarToday).setOnClickListener(v -> {
            Calendar today = Calendar.getInstance();
            shownMonth.set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), 1);
            selectedDayMillis = TaskMetadata.startOfToday();
            loadMonth();
        });
        openDailyNote.setOnClickListener(v -> openSelectedDay());
        buildWeekdays();
        repo.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMonth();
    }

    @Override
    protected void onDestroy() {
        repo.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onVaultChanged() {
        if (!isFinishing()) loadMonth();
    }

    private void changeMonth(int offset) {
        shownMonth.add(Calendar.MONTH, offset);
        shownMonth.set(Calendar.DAY_OF_MONTH, 1);
        selectedDayMillis = shownMonth.getTimeInMillis();
        loadMonth();
    }

    private void buildWeekdays() {
        weekdays.removeAllViews();
        Locale locale = Locale.getDefault();
        Calendar date = Calendar.getInstance();
        date.set(Calendar.DAY_OF_MONTH, 2);
        for (int i = 0; i < 7; i++) {
            int dayOfWeek = Calendar.MONDAY + i;
            if (dayOfWeek > Calendar.SUNDAY) dayOfWeek -= 7;
            // Set the weekday explicitly before asking the locale for its label.
            date.set(Calendar.DAY_OF_WEEK, dayOfWeek);
            String label = date.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale);
            TextView cell = new TextView(this);
            cell.setText(label);
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(11.5f);
            cell.setTextColor(Ui.themeColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(0), GridLayout.spec(i, 1f));
            params.width = 0;
            params.height = Ui.dp(this, 30);
            weekdays.addView(cell, params);
        }
    }

    private void loadMonth() {
        final int year = shownMonth.get(Calendar.YEAR);
        final int month = shownMonth.get(Calendar.MONTH);
        final int token = ++requestToken;
        progress.setVisibility(View.VISIBLE);
        Io.load(() -> {
            List<Note> notes = repo.index().all();
            List<TaskItem> tasks = TaskRepository.scan(repo);
            return new MonthData(CalendarStats.build(year, month, notes, tasks), tasks);
        }, new Io.Ok<MonthData>() {
            @Override public void onReady(MonthData value) {
                if (token != requestToken) return;
                days = value == null || value.days == null ? new ArrayList<>() : value.days;
                tasks = value == null || value.tasks == null ? new ArrayList<>() : value.tasks;
                progress.setVisibility(View.GONE);
                render();
            }

            @Override public void onError(Throwable t) {
                if (token != requestToken) return;
                progress.setVisibility(View.GONE);
                days = new ArrayList<>();
                tasks = new ArrayList<>();
                render();
            }
        });
    }

    private void render() {
        monthTitle.setText(new SimpleDateFormat("LLLL yyyy", Locale.getDefault())
                .format(shownMonth.getTime()));
        legend.setText(R.string.calendar_legend);
        grid.removeAllViews();

        int maxActivity = 0;
        for (CalendarStats.Day day : days) {
            if (day.inMonth) maxActivity = Math.max(maxActivity, day.activityCount);
        }
        for (int i = 0; i < days.size(); i++) {
            CalendarStats.Day day = days.get(i);
            addDayCell(day, i / 7, i % 7, maxActivity);
        }

        CalendarStats.Day selected = findSelectedDay();
        if (selected == null) {
            for (CalendarStats.Day day : days) {
                if (day.inMonth) {
                    selected = day;
                    selectedDayMillis = day.millis;
                    break;
                }
            }
        }
        renderSummary(selected);
    }

    private CalendarStats.Day findSelectedDay() {
        String target = CalendarStats.key(selectedDayMillis);
        for (CalendarStats.Day day : days) {
            if (day.inMonth && CalendarStats.key(day.millis).equals(target)) return day;
        }
        return null;
    }

    private void addDayCell(final CalendarStats.Day day, int row, int column,
                            int maxActivity) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(cell, 6, 5, 6, 4);

        int surface = Ui.themeColor(this, com.google.android.material.R.attr.colorSurface);
        int primary = Ui.themeColor(this, com.google.android.material.R.attr.colorPrimary);
        int outline = Ui.themeColor(this,
                com.google.android.material.R.attr.colorOutlineVariant);
        float ratio = maxActivity == 0 || !day.inMonth
                ? 0f : Math.min(1f, day.activityCount / (float) maxActivity);
        int fill = !day.inMonth ? surface
                : Ui.blend(surface, primary, day.activityCount == 0
                        ? 0f : 0.07f + ratio * 0.17f);
        boolean today = day.inMonth
                && CalendarStats.key(day.millis).equals(CalendarStats.key(
                TaskMetadata.startOfToday()));
        boolean selected = day.inMonth
                && CalendarStats.key(day.millis).equals(CalendarStats.key(selectedDayMillis));
        int stroke = selected || today ? primary : outline;
        int strokeWidth = selected ? Ui.dp(this, 2) : Ui.dp(this, 1);
        cell.setBackground(Ui.roundRect(fill, Ui.dp(this, 10), stroke, strokeWidth));

        TextView number = new TextView(this);
        number.setText(day.inMonth ? String.valueOf(day.dayOfMonth) : "");
        number.setGravity(Gravity.CENTER_HORIZONTAL);
        number.setTextSize(14f);
        number.setTypeface(android.graphics.Typeface.DEFAULT,
                today ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        number.setTextColor(today ? primary : Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        cell.addView(number, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView stats = new TextView(this);
        StringBuilder text = new StringBuilder();
        if (day.noteCount > 0) text.append("● ").append(day.noteCount);
        if (day.taskCount > 0) {
            if (text.length() > 0) text.append("  ");
            text.append("✓ ").append(day.taskCount);
        }
        if (day.openTaskCount > 0) {
            if (text.length() > 0) text.append("  ");
            text.append("! ").append(day.openTaskCount);
        }
        stats.setText(text);
        stats.setGravity(Gravity.CENTER);
        stats.setTextSize(9.5f);
        stats.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        cell.addView(stats, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 20)));

        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(row), GridLayout.spec(column, 1f));
        params.width = 0;
        params.height = Ui.dp(this, 64);
        int margin = Ui.dp(this, 2);
        params.setMargins(margin, margin, margin, margin);
        grid.addView(cell, params);

        if (!day.inMonth) {
            cell.setClickable(false);
            cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return;
        }
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(dayDescription(day));
        cell.setOnClickListener(v -> {
            selectedDayMillis = day.millis;
            render();
        });
    }

    private String dayDescription(CalendarStats.Day day) {
        String date = new SimpleDateFormat("EEEE, d MMMM yyyy",
                Locale.getDefault()).format(new Date(day.millis));
        return date + ". " + summaryText(day);
    }

    private void renderSummary(CalendarStats.Day day) {
        if (day == null) {
            summary.setText(R.string.calendar_no_data);
            openDailyNote.setVisibility(View.GONE);
            if (taskRows != null) taskRows.removeAllViews();
            return;
        }
        String date = new SimpleDateFormat("EEEE, d MMMM yyyy",
                Locale.getDefault()).format(new Date(day.millis));
        summary.setText(date + "\n" + summaryText(day));
        openDailyNote.setVisibility(View.VISIBLE);
        renderTaskRows(day);
    }

    /** Lists deadline tasks for the selected day, making this a task calendar as well as an activity map. */
    private void renderTaskRows(CalendarStats.Day day) {
        if (taskRows == null) return;
        taskRows.removeAllViews();
        TextView heading = new TextView(this);
        heading.setText(R.string.calendar_tasks_for_day);
        heading.setTextSize(12f);
        heading.setAllCaps(true);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setTextColor(Ui.themeColor(this, com.google.android.material.R.attr.colorPrimary));
        Ui.setPaddingDp(heading, 8, 16, 8, 7);
        taskRows.addView(heading);

        int count = 0;
        for (TaskItem task : tasks) {
            if (task.archived || task.dueMillis != day.millis) continue;
            taskRows.addView(taskRow(task));
            count++;
        }
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.calendar_no_tasks_for_day);
            empty.setTextSize(13f);
            empty.setTextColor(Ui.themeColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            Ui.setPaddingDp(empty, 8, 4, 8, 8);
            taskRows.addView(empty);
        }
    }

    private View taskRow(TaskItem task) {
        TextView row = new TextView(this);
        StringBuilder label = new StringBuilder(task.checked ? "✓ " : "○ ");
        if (task.priority == TaskItem.PRIORITY_HIGH) label.append("▲ ");
        else if (task.priority == TaskItem.PRIORITY_MEDIUM) label.append("◆ ");
        label.append(task.text.isEmpty() ? getString(R.string.task_untitled) : task.text);
        label.append("\n").append(task.noteTitle);
        if (task.dueTimeMinutes >= 0) {
            label.append(" · ").append(String.format(Locale.getDefault(), "%02d:%02d",
                    task.dueTimeMinutes / 60, task.dueTimeMinutes % 60));
        }
        row.setText(label);
        row.setTextSize(14f);
        row.setLines(2);
        row.setTextColor(task.priority == TaskItem.PRIORITY_HIGH
                ? Ui.themeColor(this, com.google.android.material.R.attr.colorError)
                : Ui.themeColor(this, com.google.android.material.R.attr.colorOnSurface));
        row.setBackground(Ui.rippleRect(this, Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), Ui.dp(this, 10), 0));
        Ui.setPaddingDp(row, 12, 10, 12, 10);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 4);
        row.setLayoutParams(params);
        row.setOnClickListener(v -> NoteEditorActivity.open(this, task.noteId));
        return row;
    }

    private String summaryText(CalendarStats.Day day) {
        StringBuilder text = new StringBuilder();
        if (day.noteCount > 0) {
            text.append(getResources().getQuantityString(R.plurals.calendar_notes_count,
                    day.noteCount, day.noteCount));
        }
        if (day.taskCount > 0) {
            appendLine(text, getResources().getQuantityString(R.plurals.calendar_tasks_count,
                    day.taskCount, day.taskCount));
            if (day.openTaskCount > 0) {
                appendLine(text, getResources().getQuantityString(
                        R.plurals.calendar_open_tasks_count, day.openTaskCount,
                        day.openTaskCount));
            }
        }
        if (day.activityCount > 0) {
            appendLine(text, getResources().getQuantityString(
                    R.plurals.calendar_activity_count, day.activityCount,
                    day.activityCount));
        }
        return text.length() == 0 ? getString(R.string.calendar_no_activity) : text.toString();
    }

    private static void appendLine(StringBuilder target, String value) {
        if (target.length() > 0) target.append('\n');
        target.append(value);
    }

    private static final class MonthData {
        final List<CalendarStats.Day> days;
        final List<TaskItem> tasks;

        MonthData(List<CalendarStats.Day> days, List<TaskItem> tasks) {
            this.days = days;
            this.tasks = tasks;
        }
    }

    private void openSelectedDay() {
        CalendarStats.Day day = findSelectedDay();
        if (day == null) return;
        DailyNotes.open(this, repo, new Date(day.millis));
    }
}
