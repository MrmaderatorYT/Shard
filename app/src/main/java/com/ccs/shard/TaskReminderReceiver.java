package com.ccs.shard;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ccs.shard.core.Io;
import com.ccs.shard.core.TaskItem;
import com.ccs.shard.core.TaskRepository;
import com.ccs.shard.core.VaultRepository;

/** Displays a due-task reminder and opens the owning note when tapped. */
public final class TaskReminderReceiver extends BroadcastReceiver {
    public static final String EXTRA_NOTE_ID = "note_id";
    public static final String EXTRA_TEXT = "task_text";
    public static final String EXTRA_TASK_ID = "task_id";

    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context appContext = context.getApplicationContext();
        final String noteId = intent.getStringExtra(EXTRA_NOTE_ID);
        final String text = intent.getStringExtra(EXTRA_TEXT);
        final int taskId = intent.getIntExtra(EXTRA_TASK_ID, Integer.MIN_VALUE);
        final VaultRepository repository = VaultRepository.get(appContext);
        repository.open(() -> Io.onDisk(() -> {
            boolean stillOpen = false;
            try {
                for (TaskItem task : TaskRepository.scan(repository)) {
                    if (!task.archived && !task.checked && task.stableId() == taskId
                            && (text == null ? task.text.isEmpty() : text.equals(task.text))) {
                        stillOpen = true;
                        break;
                    }
                }
            } catch (Throwable ignored) { }
            final boolean show = stillOpen;
            Io.onMain(() -> {
                if (show) showNotification(appContext, noteId, text, taskId);
                pending.finish();
            });
        }));
    }

    /** Do not show an old alarm after a task has been completed, deleted or moved. */
    private static void showNotification(Context context, String noteId, String text, int taskId) {
        Intent open = new Intent(context, NoteEditorActivity.class)
                .putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context,
                taskId == Integer.MIN_VALUE ? (noteId == null ? 0 : noteId.hashCode()) : taskId,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(
                context, ShardApp.TASK_CHANNEL)
                .setSmallIcon(R.drawable.ic_checkbox)
                .setContentTitle(context.getString(R.string.task_due_notification))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(content);
        try {
            NotificationManagerCompat.from(context).notify(
                    taskId == Integer.MIN_VALUE ? (noteId + ":" + text).hashCode() : taskId,
                    notification.build());
        } catch (SecurityException ignored) {
            // Android 13+: the Tasks screen asks for notification permission.
        }
    }
}
