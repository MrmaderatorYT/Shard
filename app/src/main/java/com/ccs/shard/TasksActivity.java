package com.ccs.shard;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.TaskDetails;
import com.ccs.shard.core.TaskItem;
import com.ccs.shard.core.TaskMetadata;
import com.ccs.shard.core.TaskRepository;
import com.ccs.shard.core.TaskTrashStore;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.Ui;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Vault-wide task view grouped by deadline. */
public final class TasksActivity extends BaseActivity {

    private LinearLayout rows;
    private ProgressBar progress;
    private LinearLayout mainToolbar;
    private LinearLayout selectionBar;
    private TextView selectionCount;
    private boolean selectionMode;
    private final Set<String> selectedTaskKeys = new LinkedHashSet<>();
    private List<TaskItem> loadedTasks = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildShell();
        requestNotifications();
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        if (rows != null) load();
    }

    @Override public void onBackPressed() {
        if (selectionMode) {
            exitSelection();
            return;
        }
        super.onBackPressed();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorSurface));
        LinearLayout toolbar = new LinearLayout(this);
        mainToolbar = toolbar;
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(toolbar, 4, 4, 10, 4);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), null));
        back.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView title = new TextView(this);
        title.setText(R.string.tasks_title);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton calendar = new ImageButton(this);
        calendar.setImageResource(R.drawable.ic_block_date);
        calendar.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        calendar.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), null));
        calendar.setContentDescription(getString(R.string.task_calendar));
        calendar.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, CalendarActivity.class)));
        toolbar.addView(calendar, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        ImageButton trash = new ImageButton(this);
        trash.setImageResource(R.drawable.ic_delete);
        trash.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        trash.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), null));
        trash.setContentDescription(getString(R.string.task_trash));
        trash.setOnClickListener(v -> showTaskTrash());
        toolbar.addView(trash, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        root.addView(toolbar);

        selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(selectionBar, 4, 4, 4, 4);
        selectionBar.setVisibility(View.GONE);
        ImageButton closeSelection = selectionButton(R.drawable.ic_close, R.string.selection_cancel);
        closeSelection.setOnClickListener(v -> exitSelection());
        selectionBar.addView(closeSelection, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        selectionCount = new TextView(this);
        selectionCount.setTextSize(17f);
        selectionCount.setTypeface(Typeface.DEFAULT_BOLD);
        selectionCount.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        selectionBar.addView(selectionCount, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton selectAll = selectionButton(R.drawable.ic_select_all, R.string.selection_select_all);
        selectAll.setOnClickListener(v -> toggleSelectAll());
        selectionBar.addView(selectAll, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        ImageButton actions = selectionButton(R.drawable.ic_menu_dots, R.string.selection_actions);
        actions.setOnClickListener(v -> showBulkTaskActions(actions));
        selectionBar.addView(actions, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        root.addView(selectionBar);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32));
        progressLp.gravity = Gravity.CENTER;
        progressLp.topMargin = Ui.dp(this, 36);
        root.addView(progress, progressLp);

        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(rows, 12, 4, 12, 40);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        Io.load(() -> TaskRepository.scan(repo), new Io.Ok<List<TaskItem>>() {
            @Override public void onReady(List<TaskItem> tasks) {
                progress.setVisibility(View.GONE);
                render(tasks == null ? new ArrayList<>() : tasks);
                TaskRepository.scheduleReminders(TasksActivity.this,
                        tasks == null ? new ArrayList<>() : tasks);
            }
        });
    }

    private void render(List<TaskItem> tasks) {
        loadedTasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        rows.removeAllViews();
        long today = TaskMetadata.startOfToday();
        addGroup(R.string.tasks_overdue, loadedTasks, item -> !item.archived && !item.checked
                && item.dueMillis > 0 && item.dueMillis < today);
        addGroup(R.string.tasks_today, loadedTasks, item -> !item.archived && !item.checked
                && item.dueMillis == today);
        addGroup(R.string.tasks_upcoming, loadedTasks, item -> !item.archived && !item.checked
                && item.dueMillis > today);
        addGroup(R.string.tasks_no_date, loadedTasks, item -> !item.archived && !item.checked
                && item.dueMillis == 0);
        addGroup(R.string.tasks_completed, loadedTasks, item -> !item.archived && item.checked);
        addGroup(R.string.tasks_archived, loadedTasks, item -> item.archived);
        if (rows.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.tasks_empty);
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(15f);
            empty.setTextColor(Ui.themeColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            Ui.setPaddingDp(empty, 28, 64, 28, 28);
            rows.addView(empty);
        }
    }

    private interface Filter { boolean accept(TaskItem item); }

    private void addGroup(int titleRes, List<TaskItem> all, Filter filter) {
        List<TaskItem> group = new ArrayList<>();
        for (TaskItem item : all) if (filter.accept(item)) group.add(item);
        if (group.isEmpty()) return;
        TextView section = new TextView(this);
        section.setText(getString(titleRes) + "  " + group.size());
        section.setTextSize(12f);
        section.setAllCaps(true);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorPrimary));
        Ui.setPaddingDp(section, 8, rows.getChildCount() == 0 ? 12 : 24, 8, 7);
        rows.addView(section);
        for (TaskItem item : group) rows.addView(taskRow(item));
    }

    private View taskRow(TaskItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        boolean selected = selectionMode && selectedTaskKeys.contains(taskKey(item));
        int surface = Ui.themeColor(this, com.google.android.material.R.attr.colorSurface);
        int fill = selected ? Ui.blend(surface, Ui.themeColor(this,
                com.google.android.material.R.attr.colorPrimary), 0.15f) : 0x00000000;
        row.setBackground(Ui.rippleRect(this, Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f),
                Ui.dp(this, 11), fill));
        Ui.setPaddingDp(row, 6, 7, 10, 7);
        MaterialCheckBox check = new MaterialCheckBox(this);
        check.setChecked(selectionMode ? selected : item.checked);
        row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        String label = item.text.isEmpty() ? getString(R.string.task_untitled) : item.text;
        title.setText(priorityMarker(item) + label);
        title.setTextSize(15f);
        title.setTextColor(priorityColor(item));
        if (item.checked) title.setAlpha(0.55f);
        text.addView(title);
        TextView meta = new TextView(this);
        StringBuilder details = new StringBuilder(item.noteTitle);
        if (item.dueMillis != 0) {
            details.append(" · ").append(DateFormat.getDateInstance(DateFormat.MEDIUM)
                    .format(new Date(item.dueMillis)));
        }
        if (item.dueTimeMinutes >= 0) details.append(" · ").append(formatTime(item.dueTimeMinutes));
        if (item.reminderMinutes >= 0) {
            details.append(" · 🔔 ").append(formatTime(item.reminderMinutes));
        }
        if (item.repeat != TaskItem.Repeat.NONE) {
            details.append(" · ").append(repeatLabel(item.repeat));
        }
        meta.setText(details);
        meta.setTextSize(11.5f);
        meta.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton more = new ImageButton(this);
        more.setImageResource(R.drawable.ic_menu_dots);
        more.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        more.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), null));
        more.setContentDescription(getString(R.string.task_actions));
        more.setOnClickListener(v -> showTaskActions(more, item));
        more.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        row.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        row.setOnClickListener(v -> {
            if (selectionMode) toggleTaskSelection(item);
            else NoteEditorActivity.open(this, item.noteId);
        });
        row.setOnLongClickListener(v -> {
            if (selectionMode) toggleTaskSelection(item);
            else enterSelection(item);
            return true;
        });
        check.setOnClickListener(v -> {
            if (selectionMode) toggleTaskSelection(item);
            else TaskRepository.setChecked(repo, item, check.isChecked(), this::load);
        });
        return row;
    }

    private ImageButton selectionButton(int iconRes, int descriptionRes) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        button.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), null));
        button.setContentDescription(getString(descriptionRes));
        return button;
    }

    // ---------------------------------------------------------- bulk selection

    private void enterSelection(TaskItem task) {
        if (task == null) return;
        selectionMode = true;
        selectedTaskKeys.clear();
        selectedTaskKeys.add(taskKey(task));
        updateSelectionChrome();
        render(loadedTasks);
    }

    private void exitSelection() {
        if (!selectionMode && selectedTaskKeys.isEmpty()) return;
        selectionMode = false;
        selectedTaskKeys.clear();
        updateSelectionChrome();
        render(loadedTasks);
    }

    private void updateSelectionChrome() {
        if (mainToolbar == null || selectionBar == null) return;
        mainToolbar.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (selectionMode && selectionCount != null) {
            selectionCount.setText(getString(R.string.selected_tasks_count, selectedTaskKeys.size()));
        }
    }

    private void toggleTaskSelection(TaskItem task) {
        String key = taskKey(task);
        if (!selectedTaskKeys.add(key)) selectedTaskKeys.remove(key);
        if (selectedTaskKeys.isEmpty()) {
            exitSelection();
            return;
        }
        updateSelectionChrome();
        render(loadedTasks);
    }

    private void toggleSelectAll() {
        if (!selectionMode) return;
        Set<String> visible = new LinkedHashSet<>();
        for (TaskItem task : loadedTasks) visible.add(taskKey(task));
        if (!visible.isEmpty() && selectedTaskKeys.containsAll(visible)) selectedTaskKeys.removeAll(visible);
        else selectedTaskKeys.addAll(visible);
        if (selectedTaskKeys.isEmpty()) exitSelection();
        else {
            updateSelectionChrome();
            render(loadedTasks);
        }
    }

    private List<TaskItem> selectedTasks() {
        List<TaskItem> selected = new ArrayList<>();
        for (TaskItem task : loadedTasks) {
            if (selectedTaskKeys.contains(taskKey(task))) selected.add(task);
        }
        return selected;
    }

    private static String taskKey(TaskItem task) {
        return task.noteId + "\u0000" + task.lineIndex;
    }

    private void showBulkTaskActions(View anchor) {
        final List<TaskItem> tasks = selectedTasks();
        if (tasks.isEmpty()) return;
        boolean allArchived = true;
        for (TaskItem task : tasks) if (!task.archived) {
            allArchived = false;
            break;
        }
        final boolean archiveNext = !allArchived;
        AnchoredMenu.vertical(this)
                .title(getString(R.string.selected_tasks_count, tasks.size()))
                .add(1, R.drawable.ic_move, getString(R.string.bulk_move))
                .add(2, R.drawable.ic_tag, getString(R.string.bulk_add_tag))
                .add(3, R.drawable.ic_tag, getString(R.string.bulk_remove_tag))
                .add(4, R.drawable.ic_archive, getString(archiveNext
                        ? R.string.bulk_archive : R.string.bulk_unarchive))
                .add(5, R.drawable.ic_filter, getString(R.string.task_bulk_properties))
                .add(6, R.drawable.ic_export, getString(R.string.bulk_export))
                .divider()
                .add(new AnchoredMenu.Item(7, R.drawable.ic_delete,
                        getString(R.string.bulk_delete)).destructive())
                .onItem(id -> {
                    switch (id) {
                        case 1: pickBulkDueDate(tasks); break;
                        case 2: showBulkTagDialog(tasks, true); break;
                        case 3: showBulkTagDialog(tasks, false); break;
                        case 4: bulkArchive(tasks, archiveNext); break;
                        case 5: showBulkProperties(tasks); break;
                        case 6: exportSelectedTasks(tasks); break;
                        case 7: confirmBulkDelete(tasks); break;
                        default: break;
                    }
                }).showAt(anchor);
    }

    private void pickBulkDueDate(final List<TaskItem> tasks) {
        Calendar selected = Calendar.getInstance();
        new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar due = Calendar.getInstance();
            due.set(year, month, day);
            final long dueMillis = TaskMetadata.startOfDay(due.getTimeInMillis());
            applyBulkDetails(tasks, details -> details.dueMillis = dueMillis, true,
                    R.string.task_bulk_moved);
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showBulkTagDialog(final List<TaskItem> tasks, final boolean add) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.bulk_tag_hint);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog().setTitle(add ? R.string.bulk_add_tag : R.string.bulk_remove_tag)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String tag = Md.normalizeTag(input.getText().toString());
                    if (tag.isEmpty()) {
                        toast(R.string.bulk_invalid_tag);
                        return;
                    }
                    exitSelection();
                    TaskRepository.bulkTag(repo, tasks, tag, add, bulkResult(R.string.task_bulk_tagged));
                }).show();
    }

    private void bulkArchive(List<TaskItem> tasks, boolean archive) {
        applyBulkDetails(tasks, details -> details.archived = archive, false,
                archive ? R.string.task_bulk_archived : R.string.task_bulk_unarchived);
    }

    private void showBulkProperties(final List<TaskItem> tasks) {
        CharSequence[] options = {getString(R.string.task_priority),
                getString(R.string.task_set_time), getString(R.string.task_set_reminder),
                getString(R.string.task_repeat)};
        dialog().setTitle(R.string.task_bulk_properties)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: chooseBulkPriority(tasks); break;
                        case 1: pickBulkTime(tasks, false); break;
                        case 2: pickBulkTime(tasks, true); break;
                        case 3: chooseBulkRepeat(tasks); break;
                        default: break;
                    }
                }).show();
    }

    private void chooseBulkPriority(final List<TaskItem> tasks) {
        final int[] priorities = {TaskItem.PRIORITY_NONE, TaskItem.PRIORITY_LOW,
                TaskItem.PRIORITY_MEDIUM, TaskItem.PRIORITY_HIGH};
        CharSequence[] labels = {getString(R.string.task_priority_none),
                getString(R.string.task_priority_low), getString(R.string.task_priority_medium),
                getString(R.string.task_priority_high)};
        dialog().setTitle(R.string.task_priority)
                .setSingleChoiceItems(labels, -1, (dialog, which) -> {
                    applyBulkDetails(tasks, details -> details.priority = priorities[which], false,
                            R.string.task_bulk_changed);
                    dialog.dismiss();
                }).show();
    }

    private void chooseBulkRepeat(final List<TaskItem> tasks) {
        final TaskItem.Repeat[] values = TaskItem.Repeat.values();
        CharSequence[] labels = {getString(R.string.task_repeat_none),
                getString(R.string.task_repeat_daily), getString(R.string.task_repeat_weekdays),
                getString(R.string.task_repeat_weekly), getString(R.string.task_repeat_monthly)};
        dialog().setTitle(R.string.task_repeat)
                .setSingleChoiceItems(labels, -1, (dialog, which) -> {
                    applyBulkDetails(tasks, details -> details.repeat = values[which], false,
                            R.string.task_bulk_changed);
                    dialog.dismiss();
                }).show();
    }

    private void pickBulkTime(final List<TaskItem> tasks, final boolean reminder) {
        int minutes = reminder ? 9 * 60 : currentMinutes();
        new TimePickerDialog(this, (picker, hour, minute) -> applyBulkDetails(tasks, details -> {
            if (reminder) details.reminderMinutes = hour * 60 + minute;
            else details.dueTimeMinutes = hour * 60 + minute;
        }, false, R.string.task_bulk_changed), minutes / 60, minutes % 60,
                android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void applyBulkDetails(List<TaskItem> tasks, TaskRepository.DetailsMutation mutation,
                                  boolean reopen, int successRes) {
        exitSelection();
        TaskRepository.bulkUpdateDetails(repo, tasks, mutation, reopen, bulkResult(successRes));
    }

    private void exportSelectedTasks(List<TaskItem> tasks) {
        exitSelection();
        Io.load(() -> TaskRepository.exportTasks(repo, tasks), new Io.Ok<File>() {
            @Override public void onReady(File file) {
                shareFile(file, "text/markdown", getString(R.string.tasks_title));
            }

            @Override public void onError(Throwable error) {
                toast(R.string.export_failed);
            }
        });
    }

    private void confirmBulkDelete(final List<TaskItem> tasks) {
        confirm(R.string.task_bulk_delete_title, getString(R.string.task_bulk_delete_body,
                tasks.size()), R.string.note_delete, () -> {
            exitSelection();
            TaskRepository.bulkDelete(repo, tasks, bulkResult(R.string.task_bulk_deleted));
        });
    }

    private Io.Result<Integer> bulkResult(final int successRes) {
        return new Io.Result<Integer>() {
            @Override public void onReady(Integer count) {
                toast(getString(successRes, count == null ? 0 : count));
                load();
            }

            @Override public void onError(Throwable error) {
                toast(R.string.bulk_operation_failed);
                load();
            }
        };
    }

    private void showTaskTrash() {
        Io.load(() -> TaskTrashStore.list(repo.vault()), new Io.Ok<List<TaskTrashStore.Entry>>() {
            @Override public void onReady(List<TaskTrashStore.Entry> entries) {
                if (entries == null || entries.isEmpty()) {
                    toast(R.string.task_trash_empty);
                    return;
                }
                showTaskTrashDialog(entries);
            }

            @Override public void onError(Throwable error) {
                toast(R.string.bulk_operation_failed);
            }
        });
    }

    private void showTaskTrashDialog(final List<TaskTrashStore.Entry> entries) {
        CharSequence[] labels = new CharSequence[entries.size()];
        final boolean[] selected = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            TaskTrashStore.Entry entry = entries.get(i);
            labels[i] = entry.line + "\n" + entry.noteTitle + " · "
                    + DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(entry.deletedAt));
        }
        dialog().setTitle(R.string.task_trash)
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.trash_restore, (dialog, which) -> {
                    List<TaskTrashStore.Entry> restore = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) if (selected[i]) restore.add(entries.get(i));
                    if (!restore.isEmpty()) TaskRepository.restoreDeleted(repo, restore,
                            bulkResult(R.string.task_bulk_restored));
                }).show();
    }

    private void showTaskActions(View anchor, TaskItem item) {
        AnchoredMenu.vertical(this)
                .title(item.text.isEmpty() ? getString(R.string.task_untitled) : item.text)
                .add(1, R.drawable.ic_checkbox, getString(R.string.task_priority))
                .add(2, R.drawable.ic_block_date, getString(R.string.task_set_due))
                .add(3, R.drawable.ic_block_date, getString(R.string.task_set_time))
                .add(4, R.drawable.ic_block_date, getString(R.string.task_set_reminder))
                .add(5, R.drawable.ic_recent, getString(R.string.task_repeat))
                .divider()
                .add(6, R.drawable.ic_arrow_right, getString(R.string.task_move_tomorrow))
                .add(7, R.drawable.ic_arrow_right, getString(R.string.task_move_next_week))
                .divider()
                .add(8, R.drawable.ic_open_in_new, getString(R.string.task_open_note))
                .onItem(id -> handleTaskAction(id, item))
                .showAt(anchor);
    }

    private void handleTaskAction(int id, TaskItem item) {
        switch (id) {
            case 1: choosePriority(item); break;
            case 2: pickDueDate(item); break;
            case 3: pickDueTime(item); break;
            case 4: pickReminder(item); break;
            case 5: chooseRepeat(item); break;
            case 6: moveTask(item, 1); break;
            case 7: moveTaskToNextWeek(item); break;
            case 8: NoteEditorActivity.open(this, item.noteId); break;
            default: break;
        }
    }

    private void choosePriority(TaskItem item) {
        final int[] priorities = {TaskItem.PRIORITY_NONE, TaskItem.PRIORITY_LOW,
                TaskItem.PRIORITY_MEDIUM, TaskItem.PRIORITY_HIGH};
        CharSequence[] labels = {getString(R.string.task_priority_none),
                getString(R.string.task_priority_low), getString(R.string.task_priority_medium),
                getString(R.string.task_priority_high)};
        int selected = 0;
        for (int i = 0; i < priorities.length; i++) {
            if (priorities[i] == item.priority) selected = i;
        }
        dialog().setTitle(R.string.task_priority)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    TaskDetails details = TaskMetadata.from(item);
                    details.priority = priorities[which];
                    TaskRepository.updateDetails(repo, item, details, false, this::load);
                    dialog.dismiss();
                }).show();
    }

    private void chooseRepeat(TaskItem item) {
        final TaskItem.Repeat[] values = TaskItem.Repeat.values();
        CharSequence[] labels = {getString(R.string.task_repeat_none),
                getString(R.string.task_repeat_daily), getString(R.string.task_repeat_weekdays),
                getString(R.string.task_repeat_weekly), getString(R.string.task_repeat_monthly)};
        dialog().setTitle(R.string.task_repeat)
                .setSingleChoiceItems(labels, item.repeat.ordinal(), (dialog, which) -> {
                    TaskDetails details = TaskMetadata.from(item);
                    details.repeat = values[which];
                    TaskRepository.updateDetails(repo, item, details, false, this::load);
                    dialog.dismiss();
                }).show();
    }

    private void pickDueDate(TaskItem item) {
        Calendar chosen = Calendar.getInstance();
        if (item.dueMillis > 0) chosen.setTimeInMillis(item.dueMillis);
        new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar due = Calendar.getInstance();
            due.set(year, month, day);
            TaskRepository.reschedule(repo, item, due.getTimeInMillis(), this::load);
        }, chosen.get(Calendar.YEAR), chosen.get(Calendar.MONTH),
                chosen.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickDueTime(TaskItem item) {
        int minutes = item.dueTimeMinutes >= 0 ? item.dueTimeMinutes : currentMinutes();
        new TimePickerDialog(this, (picker, hour, minute) -> {
            TaskDetails details = TaskMetadata.from(item);
            details.dueTimeMinutes = hour * 60 + minute;
            TaskRepository.updateDetails(repo, item, details, false, this::load);
        }, minutes / 60, minutes % 60,
                android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void pickReminder(TaskItem item) {
        int minutes = item.reminderMinutes >= 0 ? item.reminderMinutes
                : (item.dueTimeMinutes >= 0 ? item.dueTimeMinutes : 9 * 60);
        new TimePickerDialog(this, (picker, hour, minute) -> {
            TaskDetails details = TaskMetadata.from(item);
            details.reminderMinutes = hour * 60 + minute;
            TaskRepository.updateDetails(repo, item, details, false, this::load);
        }, minutes / 60, minutes % 60,
                android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void moveTask(TaskItem item, int days) {
        Calendar target = Calendar.getInstance();
        target.add(Calendar.DAY_OF_YEAR, days);
        TaskRepository.reschedule(repo, item, target.getTimeInMillis(), this::load);
    }

    private void moveTaskToNextWeek(TaskItem item) {
        Calendar target = Calendar.getInstance();
        target.add(Calendar.WEEK_OF_YEAR, 1);
        target.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        TaskRepository.reschedule(repo, item, target.getTimeInMillis(), this::load);
    }

    private int priorityColor(TaskItem item) {
        if (item.priority == TaskItem.PRIORITY_HIGH) {
            return Ui.themeColor(this, com.google.android.material.R.attr.colorError);
        }
        if (item.priority == TaskItem.PRIORITY_MEDIUM) {
            return Ui.themeColor(this, com.google.android.material.R.attr.colorPrimary);
        }
        return Ui.themeColor(this, com.google.android.material.R.attr.colorOnSurface);
    }

    private static String priorityMarker(TaskItem item) {
        if (item.priority == TaskItem.PRIORITY_HIGH) return "▲ ";
        if (item.priority == TaskItem.PRIORITY_MEDIUM) return "◆ ";
        if (item.priority == TaskItem.PRIORITY_LOW) return "• ";
        return "";
    }

    private String repeatLabel(TaskItem.Repeat repeat) {
        switch (repeat) {
            case DAILY: return getString(R.string.task_repeat_daily);
            case WEEKDAYS: return getString(R.string.task_repeat_weekdays);
            case WEEKLY: return getString(R.string.task_repeat_weekly);
            case MONTHLY: return getString(R.string.task_repeat_monthly);
            case NONE:
            default: return "";
        }
    }

    private static int currentMinutes() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    private static String formatTime(int minutes) {
        return String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }
}
