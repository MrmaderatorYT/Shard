package com.ccs.shard.core.cloud;

import android.content.Context;
import android.util.Log;

import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.Vault;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.core.VaultWrites;
import com.ccs.shard.core.cloud.drive.DriveAuth;
import com.ccs.shard.core.cloud.drive.DriveBackend;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The seam between saving a note and the note appearing in the cloud.
 *
 * <p>Call {@link #onFileWritten} from the write path - after the bytes are on
 * disk, never from a {@link VaultRepository.Listener}, because that fires
 * optimistically before the disk write and would upload the previous content.
 *
 * <p>Uploads are debounced rather than immediate. Shard autosaves while the
 * user types, so "on every save" taken literally would be a request per
 * keystroke burst; a short quiet period collapses a typing session into one
 * upload while still feeling instant. Anything that misses the window - the
 * app dies, the network is down - is picked up by {@link CloudSyncWorker}.
 *
 * <p>Deliberately on its own single thread: {@code Io.DISK} is a serial queue
 * whose ordering guarantees note saves, and parking a 30-second upload in it
 * would stall the editor.
 */
public final class CloudSyncManager implements VaultWrites.Observer {

    private static final String TAG = "CloudSyncManager";

    /** Quiet period after the last write before uploading. */
    private static final long DEBOUNCE_MS = 4_000L;

    /**
     * Structural changes wait longer: a rename fires this signal several times
     * as the index is patched, and the reconcile it triggers is a directory
     * walk rather than a single queue insert.
     */
    private static final long STRUCTURE_DEBOUNCE_MS = 10_000L;

    private static volatile CloudSyncManager instance;

    private final Context context;
    private final Prefs prefs;
    private final VaultRepository repository;
    private final CloudIndex index;
    private final CloudQueue queue;
    private final CloudSyncService service;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    private ScheduledFuture<?> pending;
    private volatile boolean reconcilePending;
    private CloudBackend backend;
    private CloudProvider backendProvider = CloudProvider.NONE;
    private String backendAccount;

    private CloudSyncManager(Context context, VaultRepository repository) {
        this.context = context.getApplicationContext();
        this.prefs = new Prefs(this.context);
        this.repository = repository;
        File sidecar = repository.vault().sidecarDir();
        this.index = new CloudIndex(new File(sidecar, "cloud-index.tsv"));
        this.queue = new CloudQueue(new File(sidecar, "cloud-queue.tsv"));
        this.service = new CloudSyncService(repository, index, queue);
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "shard-cloud");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.setDaemon(true);
                return thread;
            }
        });
        VaultWrites.setObserver(this);
    }

    public static CloudSyncManager get(Context context) {
        CloudSyncManager local = instance;
        if (local == null) {
            synchronized (CloudSyncManager.class) {
                local = instance;
                if (local == null) {
                    local = new CloudSyncManager(context, VaultRepository.get(context));
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Null when cloud sync has never been touched this process. */
    public static CloudSyncManager peek() {
        return instance;
    }

    public CloudProvider provider() {
        return prefs.cloudProvider();
    }

    public boolean enabled() {
        CloudProvider provider = provider();
        return provider != CloudProvider.NONE && provider.implemented();
    }

    public int pendingCount() {
        return queue.size();
    }

    public CloudIndex index() {
        return index;
    }

    // -------------------------------------------------------------- write hook

    /**
     * Records that a vault file changed on disk and schedules an upload.
     *
     * @param relPath vault-relative path, as {@link com.ccs.shard.core.Vault#relativize} returns.
     */
    public void onFileWritten(String relPath) {
        if (!enabled() || !CloudSyncService.mirrored(relPath)) return;
        queue.upsert(relPath);
        schedule();
    }

    /** Records that a vault file was removed and schedules the remote delete. */
    public void onFileRemoved(String relPath) {
        if (!enabled() || !CloudSyncService.mirrored(relPath)) return;
        queue.delete(relPath);
        schedule();
    }

    /** Convenience for renames and moves, which are a delete plus an upsert. */
    public void onFileMoved(String fromRelPath, String toRelPath) {
        onFileRemoved(fromRelPath);
        onFileWritten(toRelPath);
    }

    @Override public void onVaultFileWritten(File file) {
        if (!enabled() || file == null) return;
        Vault vault = repository.vault();
        File root = vault.root();
        if (root == null) return;
        // Sidecar writes land here too - including this class's own index and
        // queue files - so relativise first and let mirrored() reject them.
        // That is also what keeps persisting the index from re-queueing it.
        String rel = vault.relativize(file);
        if (!CloudSyncService.mirrored(rel)) return;
        queue.upsert(rel);
        schedule();
    }

    @Override public void onVaultStructureChanged() {
        if (!enabled()) return;
        reconcilePending = true;
        scheduleStructure();
    }

    private synchronized void schedule() {
        if (pending != null) pending.cancel(false);
        try {
            pending = executor.schedule(task, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "cannot schedule cloud sync", t);
        }
    }

    /**
     * Same debounced task on the longer structural delay. Kept separate from
     * {@link #schedule()} so a stream of saves cannot keep pushing a pending
     * reconcile out of reach.
     */
    private synchronized void scheduleStructure() {
        if (pending != null && !pending.isDone()) return;
        try {
            pending = executor.schedule(task, STRUCTURE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "cannot schedule cloud reconcile", t);
        }
    }

    /**
     * The one debounced unit of work: reconcile if something moved, then push.
     * Reconciling here rather than on every structural signal means a rename
     * storm costs one directory walk, not one per notification.
     */
    private final Runnable task = new Runnable() {
        @Override public void run() {
            if (reconcilePending) {
                reconcilePending = false;
                reconcileBlocking();
            }
            queue.flush();
            drain();
        }
    };

    /** Uploads now, skipping the debounce. Used by the settings "sync now" row. */
    public void syncNow() {
        if (!enabled()) return;
        executor.execute(new Runnable() {
            @Override public void run() {
                queue.flush();
                drain();
            }
        });
    }

    /**
     * Queues everything the remote is missing or holding a stale copy of, then
     * uploads. Called on app start and from the periodic worker.
     */
    public void reconcileAndSync() {
        if (!enabled()) return;
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    service.reconcile(provider());
                } catch (Throwable t) {
                    Log.w(TAG, "reconcile failed", t);
                }
                drain();
            }
        });
    }

    /**
     * Queues everything the remote is missing, on the calling thread.
     * For the worker, which is already off the main thread and must not
     * return before the work is done.
     *
     * @return how many operations were queued.
     */
    public int reconcileBlocking() {
        if (!enabled()) return 0;
        try {
            return service.reconcile(provider());
        } catch (Throwable t) {
            Log.w(TAG, "reconcile failed", t);
            return 0;
        }
    }

    // ------------------------------------------------------------------- drain

    /**
     * Runs one drain, blocking the calling thread. Safe to call from a Worker.
     *
     * @return the result, never null.
     */
    public CloudSyncResult drainBlocking() {
        if (!enabled()) {
            CloudSyncResult result = new CloudSyncResult();
            result.lastError = CloudException.Reason.CONFIGURATION;
            return result;
        }
        if (!draining.compareAndSet(false, true)) {
            // A drain is already in flight; its own queue read will pick up
            // whatever we would have uploaded.
            CloudSyncResult result = new CloudSyncResult();
            result.remaining = queue.size();
            return result;
        }
        try {
            CloudSyncResult result = service.drain(backend());
            prefs.setCloudLastSyncMillis(System.currentTimeMillis());
            prefs.setCloudLastError(result.lastError == null ? null : result.lastError.name());
            if (result.lastError == CloudException.Reason.AUTHENTICATION) {
                // The token is unusable; drop the backend so the next attempt
                // rebuilds it, and let settings show the reconnect prompt.
                releaseBackend();
            }
            return result;
        } finally {
            draining.set(false);
        }
    }

    private void drain() {
        try {
            CloudSyncResult result = drainBlocking();
            if (result.shouldRetry()) {
                // Hand off to WorkManager: it survives the process and waits
                // for connectivity, which a scheduled executor cannot.
                CloudSyncWorker.scheduleRetry(context);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cloud sync failed", t);
        }
    }

    /** Lazily builds the backend for the configured provider. */
    private synchronized CloudBackend backend() {
        CloudProvider provider = provider();
        String account = prefs.cloudAccount();
        if (backend != null && backendProvider == provider
                && equal(backendAccount, account)) {
            return backend;
        }
        releaseBackend();
        if (provider == CloudProvider.GOOGLE_DRIVE && account != null && !account.isEmpty()) {
            backend = new DriveBackend(context, new DriveAuth(context, account),
                    prefs.cloudFolderName());
        } else if (provider == CloudProvider.DROPBOX) {
            // Reserved: the enum and the index already carry per-row provider
            // ids, so adding a DropboxBackend needs no change here beyond this
            // branch.
            backend = null;
        }
        backendProvider = provider;
        backendAccount = account;
        return backend;
    }

    private synchronized void releaseBackend() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Throwable ignored) {
            }
        }
        backend = null;
        backendProvider = CloudProvider.NONE;
        backendAccount = null;
    }

    // ------------------------------------------------------------ (dis)connect

    /**
     * Points the mirror at a provider and account, and queues the whole vault.
     * The first sync after this uploads everything.
     */
    public void connect(final CloudProvider provider, final String account) {
        prefs.setCloudProvider(provider);
        prefs.setCloudAccount(account);
        prefs.setCloudLastError(null);
        executor.execute(new Runnable() {
            @Override public void run() {
                releaseBackend();
                // Ids from another provider are meaningless here, and keeping
                // them would make an update target a file we cannot see.
                index.retainProvider(provider);
                service.reconcile(provider);
                drain();
            }
        });
        CloudSyncWorker.schedule(context);
    }

    /**
     * Stops mirroring. Remote files are left alone - the user's notes on Drive
     * are theirs, and silently deleting them because a toggle went off would
     * be indefensible.
     */
    public void disconnect() {
        prefs.setCloudProvider(CloudProvider.NONE);
        prefs.setCloudAccount(null);
        prefs.setCloudLastError(null);
        CloudSyncWorker.cancel(context);
        executor.execute(new Runnable() {
            @Override public void run() {
                releaseBackend();
                queue.clear();
                index.clear();
            }
        });
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
