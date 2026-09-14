package com.ccs.shard.core.cloud;

import android.util.Log;

import com.ccs.shard.core.NoteFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps vault-relative paths to the remote file the provider gave us.
 *
 * <p>This is the piece that makes an update an update rather than a duplicate:
 * without a stored remote id, every save on Google Drive would create another
 * copy of the note. It also records the local mtime and size behind each
 * upload, which is how {@link CloudSyncService#reconcile} spots files that
 * changed while the queue was lost to a process death.
 *
 * <p>Persisted as TSV in {@code .shard/cloud-index.tsv}:
 * <pre>shard-cloud-index&lt;TAB&gt;1
 * path&lt;TAB&gt;provider&lt;TAB&gt;remoteId&lt;TAB&gt;rev&lt;TAB&gt;localModified&lt;TAB&gt;localSize</pre>
 *
 * <p>The provider column is per row, not per file, so switching providers - or
 * running one after another - never makes Shard hand a Drive id to Dropbox.
 * All methods are safe to call from any thread.
 */
public final class CloudIndex {

    private static final String TAG = "CloudIndex";
    private static final String HEADER = "shard-cloud-index";
    private static final int VERSION = 1;
    private static final int COLUMNS = 6;

    private final File file;
    private final Map<String, CloudEntry> entries = new HashMap<>();
    private final Object lock = new Object();
    private boolean loaded;

    public CloudIndex(File file) {
        this.file = file;
    }

    /** The recorded entry for {@code relPath}, or {@code null}. */
    public CloudEntry get(String relPath) {
        if (relPath == null) return null;
        synchronized (lock) {
            ensureLoaded();
            return entries.get(relPath);
        }
    }

    /**
     * The entry for {@code relPath} only if it belongs to {@code provider} and
     * carries a remote id. Anything else is treated as "never uploaded", which
     * is exactly what a provider switch should look like.
     */
    public CloudEntry usable(String relPath, CloudProvider provider) {
        CloudEntry entry = get(relPath);
        return entry != null && entry.usableFor(provider) ? entry : null;
    }

    public void put(String relPath, CloudEntry entry) {
        if (relPath == null || relPath.isEmpty() || entry == null) return;
        synchronized (lock) {
            ensureLoaded();
            entries.put(relPath, entry);
        }
    }

    public void remove(String relPath) {
        if (relPath == null) return;
        synchronized (lock) {
            ensureLoaded();
            entries.remove(relPath);
        }
    }

    /** Every recorded path. A copy, safe to iterate while the index mutates. */
    public Map<String, CloudEntry> snapshot() {
        synchronized (lock) {
            ensureLoaded();
            return new HashMap<>(entries);
        }
    }

    public int size() {
        synchronized (lock) {
            ensureLoaded();
            return entries.size();
        }
    }

    /** Forgets every mapping, on disk too. Used when disconnecting a provider. */
    public void clear() {
        synchronized (lock) {
            entries.clear();
            loaded = true;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Drops rows belonging to any provider other than {@code keep}. */
    public void retainProvider(CloudProvider keep) {
        synchronized (lock) {
            ensureLoaded();
            boolean changed = false;
            java.util.Iterator<Map.Entry<String, CloudEntry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().provider() != keep) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) save();
        }
    }

    // ------------------------------------------------------------- persistence

    public void save() {
        synchronized (lock) {
            StringBuilder text = new StringBuilder(64 + entries.size() * 96);
            text.append(HEADER).append('\t').append(VERSION).append('\n');
            for (Map.Entry<String, CloudEntry> row : entries.entrySet()) {
                CloudEntry entry = row.getValue();
                text.append(Tsv.esc(row.getKey())).append('\t')
                        .append(entry.provider().id()).append('\t')
                        .append(Tsv.esc(entry.remoteId())).append('\t')
                        .append(Tsv.esc(entry.rev())).append('\t')
                        .append(entry.localModified()).append('\t')
                        .append(entry.localSize()).append('\n');
            }
            try {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                // Atomic, unlike NoteIndex's cache: a torn cloud index would
                // orphan remote files instead of merely slowing a rebuild.
                NoteFile.writeAtomic(file, text.toString());
            } catch (Throwable t) {
                Log.w(TAG, "cannot persist cloud index", t);
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
            if (header == null || !header.equals(HEADER + "\t" + VERSION)) {
                // Unknown vintage: start over rather than misread ids. The
                // reconcile pass will re-upload, which is safe but not free.
                entries.clear();
                return;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < COLUMNS) continue;
                String path = Tsv.unesc(parts[0]);
                if (path.isEmpty()) continue;
                CloudProvider provider = CloudProvider.fromId(parts[1]);
                if (provider == CloudProvider.NONE) continue;
                entries.put(path, new CloudEntry(
                        provider,
                        Tsv.unesc(parts[2]),
                        Tsv.unesc(parts[3]),
                        Tsv.parseLong(parts[4]),
                        Tsv.parseLong(parts[5])));
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot read cloud index", t);
        } finally {
            NoteFile.closeQuietly(reader);
        }
    }
}
