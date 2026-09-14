package com.ccs.shard.core.cloud;

import android.util.Log;

import com.ccs.shard.core.NoteFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the vault still owes the remote, surviving process death.
 *
 * <p>Keyed by path, so a note saved twenty times while offline is one pending
 * upload, not twenty; and a note created then deleted collapses to a delete.
 * Insertion order is preserved so the oldest edit uploads first.
 *
 * <p>Persisted as TSV in {@code .shard/cloud-queue.tsv}:
 * <pre>shard-cloud-queue&lt;TAB&gt;1
 * path&lt;TAB&gt;kind&lt;TAB&gt;enqueuedMillis&lt;TAB&gt;attempts</pre>
 *
 * <p>Writes are marked dirty and persisted by {@link #flush()} rather than on
 * every mutation: a bulk edit across a thousand notes would otherwise rewrite
 * the whole file a thousand times. The queue is therefore best-effort across a
 * kill, and {@link CloudSyncService#reconcile} is what closes that gap.
 */
public final class CloudQueue {

    private static final String TAG = "CloudQueue";
    private static final String HEADER = "shard-cloud-queue";
    private static final int VERSION = 1;
    private static final int COLUMNS = 4;

    /** One pending operation. Immutable apart from the attempt counter. */
    public static final class Op {
        public final String path;
        public final CloudOpKind kind;
        public final long enqueuedMillis;
        public int attempts;

        Op(String path, CloudOpKind kind, long enqueuedMillis, int attempts) {
            this.path = path;
            this.kind = kind;
            this.enqueuedMillis = enqueuedMillis;
            this.attempts = attempts;
        }
    }

    private final File file;
    private final LinkedHashMap<String, Op> ops = new LinkedHashMap<>();
    private final Object lock = new Object();
    private boolean loaded;
    private boolean dirty;

    public CloudQueue(File file) {
        this.file = file;
    }

    /**
     * Records that {@code relPath} needs uploading.
     *
     * <p>A pending {@link CloudOpKind#DELETE} for the same path is replaced:
     * the file exists again, so the delete is moot. The original enqueue time
     * is kept when the path was already queued, so a note edited continuously
     * cannot starve the rest of the queue.
     */
    public void upsert(String relPath) {
        enqueue(relPath, CloudOpKind.UPSERT);
    }

    /** Records that {@code relPath} must be removed remotely. */
    public void delete(String relPath) {
        enqueue(relPath, CloudOpKind.DELETE);
    }

    private void enqueue(String relPath, CloudOpKind kind) {
        if (relPath == null || relPath.isEmpty()) return;
        synchronized (lock) {
            ensureLoaded();
            Op existing = ops.get(relPath);
            long since = existing != null ? existing.enqueuedMillis : System.currentTimeMillis();
            int attempts = existing != null && existing.kind == kind ? existing.attempts : 0;
            // Always a fresh instance, even when the kind is unchanged: the
            // drain holds the old one and clears it by identity, so replacing
            // it here is what stops a save that lands mid-upload from being
            // acknowledged as already done.
            ops.put(relPath, new Op(relPath, kind, since, attempts));
            dirty = true;
        }
    }

    /** Pending operations, oldest first. A copy. */
    public List<Op> pending() {
        synchronized (lock) {
            ensureLoaded();
            return new ArrayList<>(ops.values());
        }
    }

    public int size() {
        synchronized (lock) {
            ensureLoaded();
            return ops.size();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Drops {@code op} once it has been carried out - but only if nothing was
     * re-queued for that path in the meantime. Comparing identity, not path,
     * is what keeps a save that landed mid-upload from being silently lost.
     */
    public void done(Op op) {
        if (op == null) return;
        synchronized (lock) {
            ensureLoaded();
            if (ops.get(op.path) == op) {
                ops.remove(op.path);
                dirty = true;
            }
        }
    }

    /** Records a failed attempt so the drain can give up on a poison entry. */
    public void failed(Op op) {
        if (op == null) return;
        synchronized (lock) {
            ensureLoaded();
            if (ops.get(op.path) == op) {
                op.attempts++;
                dirty = true;
            }
        }
    }

    /** Forgets every pending operation, on disk too. */
    public void clear() {
        synchronized (lock) {
            ops.clear();
            loaded = true;
            dirty = false;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    // ------------------------------------------------------------- persistence

    /** Writes the queue out when it changed since the last flush. */
    public void flush() {
        synchronized (lock) {
            if (!dirty) return;
            dirty = false;
            StringBuilder text = new StringBuilder(64 + ops.size() * 64);
            text.append(HEADER).append('\t').append(VERSION).append('\n');
            for (Op op : ops.values()) {
                text.append(Tsv.esc(op.path)).append('\t')
                        .append(op.kind.id()).append('\t')
                        .append(op.enqueuedMillis).append('\t')
                        .append(op.attempts).append('\n');
            }
            try {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                NoteFile.writeAtomic(file, text.toString());
            } catch (Throwable t) {
                Log.w(TAG, "cannot persist cloud queue", t);
                dirty = true;
            }
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"), 8192);
            String header = reader.readLine();
            if (header == null || !header.equals(HEADER + "\t" + VERSION)) return;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < COLUMNS) continue;
                String path = Tsv.unesc(parts[0]);
                CloudOpKind kind = CloudOpKind.fromId(parts[1]);
                if (path.isEmpty() || kind == null) continue;
                ops.put(path, new Op(path, kind, Tsv.parseLong(parts[2]), Tsv.parseInt(parts[3])));
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot read cloud queue", t);
        } finally {
            NoteFile.closeQuietly(reader);
        }
    }

    /** Removes entries that failed too often, so one bad path cannot wedge the queue. */
    void dropExhausted(int maxAttempts) {
        synchronized (lock) {
            ensureLoaded();
            Iterator<Map.Entry<String, Op>> it = ops.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().attempts >= maxAttempts) {
                    it.remove();
                    dirty = true;
                }
            }
        }
    }
}
