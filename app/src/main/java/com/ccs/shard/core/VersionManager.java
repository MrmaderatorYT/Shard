package com.ccs.shard.core;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Local version history for notes.
 *
 * <p>Snapshots live in {@code .shard/versions/<slug>/<timestamp>.md} — outside the
 * note tree, so history never shows up as notes and never syncs into another
 * Markdown tool's view of the vault.
 *
 * <p>Autosave fires every few hundred milliseconds while typing, so writing a
 * snapshot per save would be both useless and expensive. Snapshots are therefore
 * throttled: a new one is only written when the newest existing snapshot is
 * older than {@link #MIN_INTERVAL_MS}, or when the caller explicitly forces one
 * (before a restore, before an import overwrite, on manual request).
 */
public class VersionManager {

    private static final String TAG = "ShardVersions";
    private static final int MAX_VERSIONS = 30;
    /** Minimum gap between automatic snapshots of the same note. */
    private static final long MIN_INTERVAL_MS = 5 * 60 * 1000L;

    private final File versionsRoot;

    public VersionManager(File versionsRoot) {
        this.versionsRoot = versionsRoot;
    }

    private File dirFor(String noteId) {
        File dir = new File(versionsRoot, slug(noteId));
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    /** Stable, filesystem-safe folder name for a vault-relative note id. */
    private static String slug(String noteId) {
        StringBuilder sb = new StringBuilder(noteId.length() + 8);
        for (int i = 0; i < noteId.length(); i++) {
            char c = noteId.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
            else sb.append('_');
        }
        // Append a hash so two ids that sanitise identically stay distinct.
        sb.append('-').append(Integer.toHexString(noteId.hashCode()));
        String s = sb.toString();
        return s.length() > 90 ? s.substring(s.length() - 90) : s;
    }

    /**
     * Records {@code content} as a snapshot if enough time has passed.
     *
     * @param force write regardless of the throttle interval
     * @return true when a snapshot was written
     */
    public boolean snapshot(String noteId, String title, String content, boolean force) {
        if (noteId == null || content == null) return false;
        File dir = dirFor(noteId);
        List<Version> existing = list(noteId);

        if (!existing.isEmpty()) {
            Version newest = existing.get(0);
            if (contentOf(newest).equals(content)) return false;
            if (!force && System.currentTimeMillis() - newest.timestamp < MIN_INTERVAL_MS) {
                return false;
            }
        } else if (content.trim().isEmpty()) {
            // Nothing worth keeping as the very first snapshot.
            return false;
        }

        long timestamp = System.currentTimeMillis();
        File file = new File(dir, timestamp + ".md");
        try {
            StringBuilder sb = new StringBuilder(content.length() + 96);
            sb.append("---\n");
            sb.append("shard_version: ").append(timestamp).append('\n');
            sb.append("note: ").append(noteId).append('\n');
            sb.append("title: ").append(title == null ? "" : title).append('\n');
            sb.append("---\n\n");
            sb.append(content);
            NoteFile.writeAtomic(file, sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "cannot write version for " + noteId, e);
            return false;
        }
        prune(dir);
        return true;
    }

    private void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_VERSIONS) return;
        List<File> sorted = new ArrayList<>(files.length);
        Collections.addAll(sorted, files);
        Collections.sort(sorted, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(stamp(b), stamp(a));
            }
        });
        for (int i = MAX_VERSIONS; i < sorted.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            sorted.get(i).delete();
        }
    }

    private static long stamp(File file) {
        String name = file.getName();
        int dot = name.indexOf('.');
        try {
            return Long.parseLong(dot > 0 ? name.substring(0, dot) : name);
        } catch (Throwable t) {
            return file.lastModified();
        }
    }

    /** Snapshots for a note, newest first. Content is loaded lazily. */
    public List<Version> list(String noteId) {
        List<Version> out = new ArrayList<>();
        if (noteId == null) return out;
        File dir = new File(versionsRoot, slug(noteId));
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (!file.getName().endsWith(".md")) continue;
            out.add(new Version(stamp(file), file));
        }
        Collections.sort(out, new Comparator<Version>() {
            @Override public int compare(Version a, Version b) {
                return Long.compare(b.timestamp, a.timestamp);
            }
        });
        return out;
    }

    public int count(String noteId) {
        return list(noteId).size();
    }

    /** Body of a snapshot, with its own front matter removed. */
    public String contentOf(Version version) {
        if (version == null) return "";
        try {
            String raw = NoteFile.readText(version.file);
            if (raw.startsWith("---")) {
                int end = raw.indexOf("\n---", 3);
                if (end > 0) {
                    int bodyStart = raw.indexOf('\n', end + 1);
                    if (bodyStart > 0) {
                        String body = raw.substring(bodyStart + 1);
                        return body.startsWith("\n") ? body.substring(1) : body;
                    }
                }
            }
            return raw;
        } catch (Throwable t) {
            Log.w(TAG, "cannot read version " + version.file, t);
            return "";
        }
    }

    public boolean delete(Version version) {
        return version != null && version.file.delete();
    }

    public void deleteAll(String noteId) {
        File dir = new File(versionsRoot, slug(noteId));
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    /** Renames the history folder so it follows a moved or renamed note. */
    public void reid(String oldId, String newId) {
        File from = new File(versionsRoot, slug(oldId));
        if (!from.isDirectory()) return;
        File to = new File(versionsRoot, slug(newId));
        if (to.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        from.renameTo(to);
    }

    /** A line-level diff, rendered as {@code + } / {@code - } / {@code   } prefixes. */
    public static List<String> diff(String oldContent, String newContent) {
        String[] a = (oldContent == null ? "" : oldContent).split("\n", -1);
        String[] b = (newContent == null ? "" : newContent).split("\n", -1);
        List<String> out = new ArrayList<>();

        // Longest-common-subsequence table. Capped so a huge note cannot allocate
        // a hundred-megabyte matrix on a low-memory device.
        if ((long) a.length * b.length > 400_000L) {
            out.add("~ " + a.length + " -> " + b.length + " lines (too large to diff)");
            return out;
        }

        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                out.add("  " + a[i]);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("- " + a[i++]);
            } else {
                out.add("+ " + b[j++]);
            }
        }
        while (i < a.length) out.add("- " + a[i++]);
        while (j < b.length) out.add("+ " + b[j++]);
        return out;
    }

    /** One stored snapshot. */
    public static final class Version {
        public final long timestamp;
        public final File file;

        Version(long timestamp, File file) {
            this.timestamp = timestamp;
            this.file = file;
        }

        public String formattedDate() {
            return new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                    .format(new java.util.Date(timestamp));
        }

        public long sizeBytes() {
            return file.length();
        }
    }
}
