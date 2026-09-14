package com.ccs.shard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.ccs.shard.core.AutoBackupManager;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.VaultRepository;

/** Periodic Android job that produces a complete vault ZIP even without opening the UI. */
public final class AutoBackupJobService extends JobService {

    private static final int JOB_ID = 0x53485244;

    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, AutoBackupJobService.class))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPeriodic(AutoBackupManager.INTERVAL_MS)
                .build();
        scheduler.schedule(info);
    }

    @Override public boolean onStartJob(final JobParameters params) {
        final VaultRepository repository = VaultRepository.get(this);
        repository.open(() -> Io.onDisk(() -> {
            new AutoBackupManager(AutoBackupJobService.this, repository).backupIfDue(false);
            jobFinished(params, false);
        }));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}
