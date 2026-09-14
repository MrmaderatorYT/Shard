package com.ccs.shard.editor;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.View;

import com.ccs.shard.R;
import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.block.Block;
import com.ccs.shard.block.BlockAdapter;
import com.ccs.shard.block.BlockDocument;
import com.ccs.shard.block.BlockType;
import com.ccs.shard.block.BlockTypeUi;
import com.ccs.shard.block.SlashCommand;
import com.ccs.shard.core.TaskDetails;
import com.ccs.shard.core.TaskItem;
import com.ccs.shard.core.TaskMetadata;
import com.ccs.shard.ui.AnchoredMenu;

import java.util.Calendar;
import java.util.List;

/**
 * Everything reachable from a single block: its long-press menu, "Turn into",
 * the insert palette and the task due-date picker.
 *
 * <p>Split out of the editor activity, which had grown to fourteen hundred lines
 * by owning this alongside note actions, exporting, intent handling and
 * autosave. This class knows about one block at one position and nothing else.
 */
public final class BlockActions {

    private static final int MENU_TURN_INTO = 1;
    private static final int MENU_DUPLICATE = 2;
    private static final int MENU_MOVE_UP = 3;
    private static final int MENU_MOVE_DOWN = 4;
    private static final int MENU_COPY = 5;
    private static final int MENU_DELETE = 6;
    private static final int MENU_DUE_DATE = 7;
    private static final int MENU_DUE_TIME = 8;
    private static final int MENU_REMINDER = 9;
    private static final int MENU_PRIORITY = 10;
    private static final int MENU_REPEAT = 11;
    private static final int MENU_TOMORROW = 12;
    private static final int MENU_NEXT_WEEK = 13;

    /** Notification permission request code for task reminders. */
    private static final int REQUEST_NOTIFICATIONS = 43;

    /** What this class needs from the editor screen. */
    public interface Host {
        BlockDocument document();
        BlockAdapter adapter();
        /** Content changed; the editor schedules a save. */
        void onDocumentEdited();
        void copyBlockMarkdown(String markdown);
    }

    /** Applies a chosen palette command; the editor owns the command semantics. */
    public interface CommandSink {
        void apply(SlashCommand command, int position);
    }

    private final BaseActivity activity;
    private final Host host;

    public BlockActions(BaseActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    // ---------------------------------------------------------------- block menu

    public void showBlockMenu(final View anchor, final int position) {
        final BlockDocument document = host.document();
        final Block block = document.get(position);
        if (block == null) return;

        AnchoredMenu menu = AnchoredMenu.vertical(activity)
                .title(activity.getString(BlockTypeUi.nameRes(block.type)))
                .add(MENU_TURN_INTO, R.drawable.ic_block_text,
                        activity.getString(R.string.block_turn_into))
                .divider();
        if (block.type == BlockType.TODO) {
            menu.add(MENU_DUE_DATE, R.drawable.ic_block_date,
                    activity.getString(R.string.task_set_due))
                    .add(MENU_DUE_TIME, R.drawable.ic_block_date,
                            activity.getString(R.string.task_set_time))
                    .add(MENU_REMINDER, R.drawable.ic_block_date,
                            activity.getString(R.string.task_set_reminder))
                    .add(MENU_PRIORITY, R.drawable.ic_checkbox,
                            activity.getString(R.string.task_priority))
                    .add(MENU_REPEAT, R.drawable.ic_recent,
                            activity.getString(R.string.task_repeat))
                    .divider()
                    .add(MENU_TOMORROW, R.drawable.ic_arrow_right,
                            activity.getString(R.string.task_move_tomorrow))
                    .add(MENU_NEXT_WEEK, R.drawable.ic_arrow_right,
                            activity.getString(R.string.task_move_next_week))
                    .divider();
        }
        menu.add(new AnchoredMenu.Item(MENU_MOVE_UP, R.drawable.ic_arrow_up,
                        activity.getString(R.string.block_move_up)).enabled(position > 0))
                .add(new AnchoredMenu.Item(MENU_MOVE_DOWN, R.drawable.ic_arrow_down,
                        activity.getString(R.string.block_move_down))
                        .enabled(position < document.size() - 1))
                .add(MENU_DUPLICATE, R.drawable.ic_copy,
                        activity.getString(R.string.block_duplicate))
                .add(MENU_COPY, R.drawable.ic_copy,
                        activity.getString(R.string.block_copy))
                .divider()
                .add(new AnchoredMenu.Item(MENU_DELETE, R.drawable.ic_delete,
                        activity.getString(R.string.block_delete)).destructive())
                .onItem(id -> handle(id, anchor, position))
                .showAt(anchor);
    }

    private void handle(int id, View anchor, int position) {
        BlockAdapter adapter = host.adapter();
        switch (id) {
            case MENU_TURN_INTO:
                showTurnInto(anchor, position);
                break;
            case MENU_MOVE_UP:
                adapter.moveBlock(position, position - 1);
                break;
            case MENU_MOVE_DOWN:
                adapter.moveBlock(position, position + 1);
                break;
            case MENU_DUPLICATE:
                adapter.duplicateBlock(position);
                break;
            case MENU_COPY: {
                Block block = host.document().get(position);
                if (block != null) {
                    BlockDocument single = new BlockDocument();
                    single.add(block.copy());
                    host.copyBlockMarkdown(single.toMarkdown());
                }
                break;
            }
            case MENU_DELETE:
                adapter.deleteBlock(position);
                break;
            case MENU_DUE_DATE:
                pickTaskDueDate(position);
                break;
            case MENU_DUE_TIME:
                pickTaskDueTime(position);
                break;
            case MENU_REMINDER:
                pickTaskReminder(position);
                break;
            case MENU_PRIORITY:
                chooseTaskPriority(position);
                break;
            case MENU_REPEAT:
                chooseTaskRepeat(position);
                break;
            case MENU_TOMORROW:
                moveTask(position, 1);
                break;
            case MENU_NEXT_WEEK:
                moveTaskToNextWeek(position);
                break;
            default:
                break;
        }
    }

    public void showTurnInto(View anchor, final int position) {
        final BlockType[] options = BlockTypeUi.convertibleTypes();
        Block block = host.document().get(position);
        AnchoredMenu menu = AnchoredMenu.vertical(activity)
                .title(activity.getString(R.string.block_turn_into));
        for (int i = 0; i < options.length; i++) {
            menu.add(new AnchoredMenu.Item(i, BlockTypeUi.iconRes(options[i]),
                    activity.getString(BlockTypeUi.nameRes(options[i])))
                    .checked(block != null && block.type == options[i]));
        }
        menu.onItem(id -> {
            if (id >= 0 && id < options.length) host.adapter().convert(position, options[id]);
        }).showAt(anchor);
    }

    /** The full insert palette, for the toolbar's "+" button. */
    public void showInsertMenu(View anchor, final int position, final CommandSink sink) {
        final List<SlashCommand> commands = SlashCommand.all();
        AnchoredMenu menu = AnchoredMenu.vertical(activity)
                .title(activity.getString(R.string.slash_menu_title));
        for (int i = 0; i < commands.size(); i++) {
            SlashCommand command = commands.get(i);
            menu.add(i, command.iconRes, activity.getString(command.titleRes));
        }
        menu.onItem(id -> {
            if (id >= 0 && id < commands.size()) sink.apply(commands.get(id), position);
        }).showAt(anchor);
    }

    // ---------------------------------------------------------------- due dates

    /**
     * Attaches a due date to a task block, written inline as {@code 📅 yyyy-MM-dd}
     * so it survives in the Markdown and other tools can see it.
     */
    public void pickTaskDueDate(final int position) {
        requestNotificationPermissionIfNeeded();
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(activity, (picker, year, month, day) -> {
            Block block = host.document().get(position);
            if (block == null || block.type != BlockType.TODO) return;
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day);
            TaskDetails details = TaskMetadata.parse(block.text);
            details.dueMillis = TaskMetadata.startOfDay(selected.getTimeInMillis());
            applyTaskDetails(position, details);
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** Sets a time of day. The date remains optional, so an author can plan it later. */
    public void pickTaskDueTime(final int position) {
        final TaskDetails details = detailsAt(position);
        if (details == null) return;
        int minutes = details.dueTimeMinutes >= 0 ? details.dueTimeMinutes
                : Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60
                + Calendar.getInstance().get(Calendar.MINUTE);
        new TimePickerDialog(activity, (picker, hour, minute) -> {
            TaskDetails updated = detailsAt(position);
            if (updated == null) return;
            updated.dueTimeMinutes = hour * 60 + minute;
            applyTaskDetails(position, updated);
        }, minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(activity)).show();
    }

    /** Sets the notification time on the task's due date. */
    public void pickTaskReminder(final int position) {
        requestNotificationPermissionIfNeeded();
        final TaskDetails details = detailsAt(position);
        if (details == null) return;
        int minutes = details.reminderMinutes >= 0 ? details.reminderMinutes
                : (details.dueTimeMinutes >= 0 ? details.dueTimeMinutes : 9 * 60);
        new TimePickerDialog(activity, (picker, hour, minute) -> {
            TaskDetails updated = detailsAt(position);
            if (updated == null) return;
            updated.reminderMinutes = hour * 60 + minute;
            applyTaskDetails(position, updated);
        }, minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(activity)).show();
    }

    private void chooseTaskPriority(final int position) {
        TaskDetails details = detailsAt(position);
        if (details == null) return;
        final int[] priorities = {TaskItem.PRIORITY_NONE, TaskItem.PRIORITY_LOW,
                TaskItem.PRIORITY_MEDIUM, TaskItem.PRIORITY_HIGH};
        CharSequence[] labels = {activity.getString(R.string.task_priority_none),
                activity.getString(R.string.task_priority_low),
                activity.getString(R.string.task_priority_medium),
                activity.getString(R.string.task_priority_high)};
        int selected = 0;
        for (int i = 0; i < priorities.length; i++) {
            if (priorities[i] == details.priority) selected = i;
        }
        activity.dialogBuilder().setTitle(R.string.task_priority)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    TaskDetails updated = detailsAt(position);
                    if (updated != null) {
                        updated.priority = priorities[which];
                        applyTaskDetails(position, updated);
                    }
                    dialog.dismiss();
                }).show();
    }

    private void chooseTaskRepeat(final int position) {
        TaskDetails details = detailsAt(position);
        if (details == null) return;
        final TaskItem.Repeat[] values = TaskItem.Repeat.values();
        CharSequence[] labels = {activity.getString(R.string.task_repeat_none),
                activity.getString(R.string.task_repeat_daily),
                activity.getString(R.string.task_repeat_weekdays),
                activity.getString(R.string.task_repeat_weekly),
                activity.getString(R.string.task_repeat_monthly)};
        int selected = details.repeat == null ? 0 : details.repeat.ordinal();
        activity.dialogBuilder().setTitle(R.string.task_repeat)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    TaskDetails updated = detailsAt(position);
                    if (updated != null) {
                        updated.repeat = values[which];
                        applyTaskDetails(position, updated);
                    }
                    dialog.dismiss();
                }).show();
    }

    private void moveTask(int position, int days) {
        TaskDetails details = detailsAt(position);
        if (details == null) return;
        Calendar target = Calendar.getInstance();
        target.add(Calendar.DAY_OF_YEAR, days);
        details.dueMillis = TaskMetadata.startOfDay(target.getTimeInMillis());
        applyTaskDetails(position, details);
    }

    private void moveTaskToNextWeek(int position) {
        TaskDetails details = detailsAt(position);
        if (details == null) return;
        Calendar target = Calendar.getInstance();
        target.add(Calendar.WEEK_OF_YEAR, 1);
        target.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        details.dueMillis = TaskMetadata.startOfDay(target.getTimeInMillis());
        applyTaskDetails(position, details);
    }

    private TaskDetails detailsAt(int position) {
        Block block = host.document().get(position);
        if (block == null || block.type != BlockType.TODO) return null;
        return TaskMetadata.parse(block.text);
    }

    private void applyTaskDetails(int position, TaskDetails details) {
        Block block = host.document().get(position);
        if (block == null || block.type != BlockType.TODO) return;
        block.text = TaskMetadata.write(block.text, details);
        host.adapter().notifyItemChanged(position);
        host.adapter().requestFocusAtEnd(position);
        host.onDocumentEdited();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity,
                android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        androidx.core.app.ActivityCompat.requestPermissions(activity,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }
}
