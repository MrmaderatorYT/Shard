package com.ccs.shard.core;

import android.content.Context;
import android.util.Log;

import com.ccs.shard.io.Exporter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Creates daily, rotating ZIP archives of the complete portable vault. */
public final class AutoBackupManager {

    private static final String TAG = "ShardAutoBackup";
    public static final long INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int KEEP_BACKUPS = 7;
    private static final Object BACKUP_LOCK = new Object();

    private final Prefs prefs;
    private final VaultRepository repository;

    public AutoBackupManager(Context context, VaultRepository repository) {
        this.prefs = new Prefs(context);
        this.repository = repository;
    }

    /** Blocking; callers must use the disk executor. */
    public File backupIfDue(boolean force) {
        synchronized (BACKUP_LOCK) {
            long now = System.currentTimeMillis();
            if (!force && now - prefs.lastAutoBackupMillis() < INTERVAL_MS) return null;
            File directory = repository.vault().automaticBackupsDir();
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                    .format(new Date(now));
            File target = new File(directory, "shard-vault-" + stamp + ".zip");
            try {
                new Exporter(repository).exportVaultNow(target);
                prefs.setLastAutoBackupMillis(now);
                prune(directory);
                return target;
            } catch (Throwable error) {
                Log.e(TAG, "automatic vault backup failed", error);
                return null;
            }
        }
    }

    public List<File> backups() {
        File[] files = repository.vault().automaticBackupsDir().listFiles(
                file -> file.isFile() && file.getName().endsWith(".zip"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new ArrayList<>(Arrays.asList(files));
    }

    private static void prune(File directory) {
        File[] files = directory.listFiles(file ->
                file.isFile() && file.getName().endsWith(".zip"));
        if (files == null || files.length <= KEEP_BACKUPS) return;
        // java.util.Comparator.comparingLong/reversed are only desugared from
        // API 24 onward on this Android toolchain; the equivalent lambda keeps
        // the rotating backup policy available down to minSdk 21.
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP_BACKUPS; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
