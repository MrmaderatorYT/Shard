package com.ccs.shard;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.ccs.shard.core.AutoBackupManager;
import com.ccs.shard.core.FolderSync;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Periodic SAF-folder sync that preserves conflicts and reports them to the user. */
public final class VaultSyncWorker extends Worker {

    public static final String CHANNEL_ID = "vault_sync_conflicts";
    private static final String UNIQUE_WORK_NAME = "shard-vault-folder-sync";

    public VaultSyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    /** Safe to call at startup and after the user connects a sync folder. */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                VaultSyncWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(UNIQUE_WORK_NAME)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK_NAME);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        Prefs prefs = new Prefs(context);
        String savedUri = prefs.syncTreeUri();
        if (savedUri == null || savedUri.isEmpty()) return Result.success();

        VaultRepository repository = VaultRepository.get(context);
        CountDownLatch ready = new CountDownLatch(1);
        repository.open(ready::countDown);
        try {
            if (!ready.await(45, TimeUnit.SECONDS)) return Result.retry();
            new AutoBackupManager(context, repository).backupIfDue(false);
            FolderSync.Result result = new FolderSync(context, repository)
                    .sync(Uri.parse(savedUri));
            if (result.errors > 0) return Result.retry();
            prefs.setLastSyncMillis(System.currentTimeMillis());
            if (result.conflicts > 0) showConflictNotification(context, result.conflicts);
            return Result.success();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Throwable error) {
            return Result.retry();
        }
    }

    private static void showConflictNotification(Context context, int conflicts) {
        Intent open = new Intent(context, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(context, 0, open, flags);
        String body = context.getResources().getQuantityString(
                R.plurals.sync_conflict_body, conflicts, conflicts);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_backup)
                .setContentTitle(context.getString(R.string.sync_conflict_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(context).notify(
                    (UNIQUE_WORK_NAME + conflicts).hashCode(), notification.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS is user-controlled on Android 13+.
        }
    }
}
