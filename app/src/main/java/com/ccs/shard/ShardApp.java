package com.ccs.shard;

import android.app.Application;
import android.content.res.Configuration;

import com.ccs.shard.block.ImageLoader;
import com.ccs.shard.core.AutoBackupManager;
import com.ccs.shard.core.CrashReporter;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.core.cloud.CloudSyncManager;
import com.ccs.shard.core.cloud.CloudSyncWorker;

/**
 * Holds the app's single long-lived objects.
 *
 * <p>The previous version created a new file manager in every activity's
 * {@code onCreate}, and each one rescanned the whole vault — so opening a note
 * re-read every file on disk. One repository, created once, is the single biggest
 * performance change in this rework.
 */
public final class ShardApp extends Application {

    public static final String TASK_CHANNEL = "task_due_dates";

    private static ShardApp instance;

    private Prefs prefs;
    private VaultRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        CrashReporter.install(this);
        repository = VaultRepository.get(this);
        ImageLoader.init(repository, getResources().getDisplayMetrics().widthPixels);
        createNotificationChannels();
        AutoBackupJobService.schedule(this);
        VaultSyncWorker.schedule(this);

        // Open the vault straight away: by the time the first list renders the
        // index is usually warm, and the work happens off the main thread anyway.
        repository.open(() -> {
            seedWelcomeNote();
            syncConfiguredFolder();
            scheduleTaskReminders();
            startCloudMirror();
            Io.onDisk(() -> new AutoBackupManager(this, repository).backupIfDue(false));
        });
        repository.pruneTrash(prefs.trashRetentionDays());
    }

    /**
     * Wakes the cloud mirror if one is configured.
     *
     * <p>Constructing the manager is also what registers the vault write
     * observer, so this has to happen even when there is nothing to upload -
     * otherwise the first save of the session would go unnoticed. The
     * reconcile catches whatever the last session's queue lost to a kill.
     */
    private void startCloudMirror() {
        try {
            CloudSyncManager cloud = CloudSyncManager.get(this);
            if (!cloud.enabled()) return;
            CloudSyncWorker.schedule(this);
            cloud.reconcileAndSync();
        } catch (Throwable t) {
            android.util.Log.w("ShardApp", "cannot start cloud mirror", t);
        }
    }

    private void createNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        android.app.NotificationChannel channel = new android.app.NotificationChannel(
                TASK_CHANNEL, getString(R.string.task_channel_name),
                android.app.NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(getString(R.string.task_channel_description));
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel sync = new android.app.NotificationChannel(
                        VaultSyncWorker.CHANNEL_ID, getString(R.string.sync_conflict_channel_name),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT);
                sync.setDescription(getString(R.string.sync_conflict_channel_description));
                manager.createNotificationChannel(sync);
            }
        }
    }

    /**
     * Writes one note on the very first launch, into an otherwise empty vault.
     *
     * <p>An empty-state screen can say a feature exists; a note the user can
     * actually poke at teaches it. This one is where the slash palette, the
     * Markdown shortcuts and the long-press menus get introduced — the three
     * things that are not discoverable by looking at the screen.
     */
    private void seedWelcomeNote() {
        if (prefs.onboarded()) return;
        prefs.setOnboarded(true);
        if (repository.index().size() > 0) return;
        repository.createNote(getString(R.string.welcome_note_title), "",
                getString(R.string.welcome_note_body));
    }

    /** A configured SAF folder is refreshed once at startup; manual Sync remains in Settings. */
    private void syncConfiguredFolder() {
        String saved = prefs.syncTreeUri();
        if (saved == null || System.currentTimeMillis() - prefs.lastSyncMillis() < 60_000L) return;
        com.ccs.shard.core.Io.load(() -> new com.ccs.shard.core.FolderSync(this, repository)
                .sync(android.net.Uri.parse(saved)),
                new com.ccs.shard.core.Io.Ok<com.ccs.shard.core.FolderSync.Result>() {
                    @Override public void onReady(com.ccs.shard.core.FolderSync.Result result) {
                        prefs.setLastSyncMillis(System.currentTimeMillis());
                    }
                });
    }

    private void scheduleTaskReminders() {
        com.ccs.shard.core.Io.load(() -> com.ccs.shard.core.TaskRepository.scan(repository),
                new com.ccs.shard.core.Io.Ok<java.util.List<com.ccs.shard.core.TaskItem>>() {
                    @Override public void onReady(
                            java.util.List<com.ccs.shard.core.TaskItem> tasks) {
                        com.ccs.shard.core.TaskRepository.scheduleReminders(
                                ShardApp.this, tasks);
                    }
                });
    }

    public static ShardApp get() { return instance; }

    public Prefs prefs() { return prefs; }

    public VaultRepository repository() { return repository; }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) ImageLoader.clear();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ImageLoader.init(repository, getResources().getDisplayMetrics().widthPixels);
    }
}
