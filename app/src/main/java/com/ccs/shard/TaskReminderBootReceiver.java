package com.ccs.shard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.ccs.shard.core.Io;
import com.ccs.shard.core.TaskRepository;
import com.ccs.shard.core.VaultRepository;

/** Restores task alarms after a device restart or after this app is updated. */
public final class TaskReminderBootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context appContext = context.getApplicationContext();
        final VaultRepository repository = VaultRepository.get(appContext);
        repository.open(() -> Io.onDisk(() -> {
            try {
                TaskRepository.scheduleReminders(appContext, TaskRepository.scan(repository));
            } finally {
                pending.finish();
            }
        }));
    }
}
