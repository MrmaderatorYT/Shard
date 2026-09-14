package com.ccs.shard.core.cloud;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.ccs.shard.core.VaultRepository;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Finishes cloud uploads the foreground could not.
 *
 * <p>Two roles. The one-shot retry is enqueued when a drain ends with work
 * left over, and waits for connectivity - which the in-process debounce
 * executor cannot do, since it dies with the process. The periodic job is the
 * safety net: it reconciles, so a queue lost to a kill, or a file changed by
 * an import or a folder sync, still reaches the remote.
 */
public final class CloudSyncWorker extends Worker {

    private static final String PERIODIC_WORK = "shard-cloud-sync";
    private static final String RETRY_WORK = "shard-cloud-sync-retry";

    public CloudSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        CloudSyncManager manager = CloudSyncManager.get(context);
        if (!manager.enabled()) return Result.success();

        // VaultRepository.open is asynchronous; the worker has no looper to
        // wait on, so block on a latch the way VaultSyncWorker does.
        VaultRepository repository = VaultRepository.get(context);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            repository.open(new Runnable() {
                @Override public void run() { ready.countDown(); }
            });
            if (!ready.await(45, TimeUnit.SECONDS)) return Result.retry();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Throwable t) {
            return Result.retry();
        }

        try {
            manager.reconcileBlocking();
            CloudSyncResult result = manager.drainBlocking();
            // Auth and quota failures do not come back with shouldRetry, so
            // they land here as a success: only the user can clear them, and
            // a retry loop would just drain the battery.
            return result.shouldRetry() ? Result.retry() : Result.success();
        } catch (Throwable t) {
            return Result.retry();
        }
    }

    /** Daily reconcile-and-push, for whatever the foreground missed. */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CloudSyncWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    /**
     * Retries a drain as soon as there is a network. KEEP, not REPLACE: a
     * burst of saves while offline must not keep pushing the attempt back.
     */
    public static void scheduleRetry(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CloudSyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(RETRY_WORK)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                RETRY_WORK, ExistingWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(PERIODIC_WORK);
        manager.cancelUniqueWork(RETRY_WORK);
    }
}
