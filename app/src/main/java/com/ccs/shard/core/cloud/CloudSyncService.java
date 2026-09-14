package com.ccs.shard.core.cloud;

import android.util.Log;

import com.ccs.shard.core.Vault;
import com.ccs.shard.core.VaultRepository;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * Pushes the vault's pending changes to whichever {@link CloudBackend} is
 * configured.
 *
 * <p>One-way by design: the device is the source of truth and the remote is a
 * mirror. That is what the user asked for - "write on the device, and every
 * save updates the cloud" - and it sidesteps the hard half of sync. The
 * {@link CloudEntry#rev()} column is recorded anyway so a future two-way mode
 * can detect remote edits without a migration.
 *
 * <p>Blocking; call from {@link CloudSyncManager}'s executor or a Worker.
 */
public final class CloudSyncService {

    private static final String TAG = "CloudSyncService";

    /** After this many failures a path is dropped so it cannot wedge the queue. */
    private static final int MAX_ATTEMPTS = 5;

    /** Persist progress every so often, so a kill mid-drain loses little. */
    private static final int FLUSH_EVERY = 20;

    private static final String ATTACHMENTS_PREFIX = "attachments/";

    private final VaultRepository repository;
    private final CloudIndex index;
    private final CloudQueue queue;

    public CloudSyncService(VaultRepository repository, CloudIndex index, CloudQueue queue) {
        this.repository = repository;
        this.index = index;
        this.queue = queue;
    }

    /**
     * Carries out every pending operation it can.
     *
     * <p>Stops early on a failure the retry cannot fix - no network, expired
     * consent, a full account - because grinding through hundreds of paths to
     * collect the same error wastes battery and Drive quota.
     */
    public CloudSyncResult drain(CloudBackend backend) {
        CloudSyncResult result = new CloudSyncResult();
        if (backend == null) {
            result.lastError = CloudException.Reason.CONFIGURATION;
            return result;
        }
        queue.dropExhausted(MAX_ATTEMPTS);
        List<CloudQueue.Op> pending = queue.pending();
        if (pending.isEmpty()) return result;

        try {
            backend.connect();
        } catch (CloudException e) {
            result.lastError = e.reason();
            result.remaining = pending.size();
            return result;
        }

        Vault vault = repository.vault();
        int since = 0;
        boolean indexDirty = false;

        for (CloudQueue.Op op : pending) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                if (op.kind == CloudOpKind.DELETE) {
                    backend.remove(op.path, index.usable(op.path, backend.provider()));
                    index.remove(op.path);
                    result.deleted++;
                } else {
                    File local = vault.resolve(op.path);
                    if (!local.isFile()) {
                        // Created and removed again before we got here. The
                        // matching delete is either queued or unnecessary.
                        queue.done(op);
                        continue;
                    }
                    CloudEntry entry = backend.upload(op.path, local,
                            index.usable(op.path, backend.provider()));
                    index.put(op.path, entry);
                    result.uploaded++;
                }
                indexDirty = true;
                queue.done(op);
            } catch (CloudException e) {
                result.failed++;
                result.lastError = e.reason();
                queue.failed(op);
                if (!e.retryable()) {
                    Log.w(TAG, "stopping drain: " + e.reason());
                    break;
                }
                if (e.reason() == CloudException.Reason.NETWORK) break;
            } catch (Throwable t) {
                result.failed++;
                result.lastError = CloudException.Reason.REMOTE;
                queue.failed(op);
                Log.w(TAG, "operation failed", t);
            }
            if (++since >= FLUSH_EVERY) {
                since = 0;
                if (indexDirty) {
                    index.save();
                    indexDirty = false;
                }
                queue.flush();
            }
        }

        if (indexDirty) index.save();
        queue.flush();
        result.remaining = queue.size();
        return result;
    }

    /**
     * Re-derives the queue from the vault, for everything the index says is
     * stale or missing.
     *
     * <p>The queue is flushed lazily and so can be lost to a process kill;
     * this is the backstop that makes the whole design self-healing rather
     * than silently drifting. Cheap - a directory walk and an mtime compare,
     * no network - so it can run on every app start and in the periodic
     * worker.
     */
    public int reconcile(CloudProvider provider) {
        Vault vault = repository.vault();
        File root = vault.root();
        if (root == null || !root.isDirectory()) return 0;

        Map<String, CloudEntry> known = index.snapshot();
        int queued = 0;

        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                String name = child.getName();
                if (child.isDirectory()) {
                    // Skip the sidecar: version history, trash and the cloud
                    // bookkeeping itself are device-local by design.
                    if (name.equals(".shard")) continue;
                    if (name.startsWith(".")) continue;
                    stack.push(child);
                    continue;
                }
                String rel = vault.relativize(child);
                if (!mirrored(rel)) continue;
                CloudEntry entry = known.remove(rel);
                if (entry == null || entry.provider() != provider
                        || entry.localModified() != child.lastModified()
                        || entry.localSize() != child.length()) {
                    queue.upsert(rel);
                    queued++;
                }
            }
        }

        // Anything left in the index no longer exists locally.
        for (Map.Entry<String, CloudEntry> stale : known.entrySet()) {
            if (stale.getValue().provider() != provider) continue;
            queue.delete(stale.getKey());
            queued++;
        }

        if (queued > 0) queue.flush();
        return queued;
    }

    /**
     * Which vault files the mirror carries, keyed by vault-relative path:
     * notes, LaTeX and canvases anywhere, plus anything the user attached.
     *
     * <p>Attachments are matched by their folder rather than by extension,
     * because that folder holds whatever the user imported - images, PDFs,
     * audio - and an extension allowlist would silently drop the unusual ones.
     * Everything else that happens to sit in the vault root belongs to some
     * other app, and uploading it would be a surprise.
     */
    public static boolean mirrored(String relPath) {
        if (relPath == null || relPath.isEmpty()) return false;
        // Any dot-segment disqualifies the whole path, not just a dot-name:
        // checking only the last segment would have let the entire version
        // history under .shard/versions upload as ordinary notes.
        if (relPath.charAt(0) == '.' || relPath.contains("/.")) return false;
        int slash = relPath.lastIndexOf('/');
        String name = slash < 0 ? relPath : relPath.substring(slash + 1);
        if (relPath.startsWith(ATTACHMENTS_PREFIX)) return true;
        String lower = name.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".tex") || lower.endsWith(".canvas");
    }
}
