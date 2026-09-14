package com.ccs.shard.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conflict-preserving two-way sync between two ordinary directory trees. */
public final class FileTreeSync {

    public static final class Result {
        public int fromRight;
        public int toRight;
        public int conflicts;
    }

    private static final class Stamp {
        long leftTime;
        long leftSize;
        long rightTime;
        long rightSize;
    }

    private final File leftRoot;
    private final File rightRoot;
    private final File stateFile;

    public FileTreeSync(File leftRoot, File rightRoot, File stateFile) {
        this.leftRoot = leftRoot;
        this.rightRoot = rightRoot;
        this.stateFile = stateFile;
    }

    public Result sync() throws Exception {
        if (!rightRoot.exists() && !rightRoot.mkdirs()) {
            throw new java.io.IOException("Cannot create Git workspace");
        }
        Map<String, File> left = collect(leftRoot);
        Map<String, File> right = collect(rightRoot);
        Map<String, Stamp> previous = readState();
        Set<String> paths = new HashSet<>();
        paths.addAll(left.keySet());
        paths.addAll(right.keySet());
        Result result = new Result();

        for (String path : paths) {
            File local = left.get(path);
            File git = right.get(path);
            if (local == null) {
                copy(git, new File(leftRoot, path));
                result.fromRight++;
                continue;
            }
            if (git == null) {
                copy(local, new File(rightRoot, path));
                result.toRight++;
                continue;
            }
            if (sameContent(local, git)) continue;

            Stamp old = previous.get(path);
            boolean localChanged = old == null || changed(local, old.leftTime, old.leftSize);
            boolean gitChanged = old == null || changed(git, old.rightTime, old.rightSize);
            if (localChanged && gitChanged) {
                // The vault is the primary working copy. Keep the Git version beside it,
                // then let the vault version become the next commit.
                copy(git, conflictFile(local));
                copy(local, git);
                result.toRight++;
                result.conflicts++;
            } else if (localChanged) {
                copy(local, git);
                result.toRight++;
            } else if (gitChanged) {
                copy(git, local);
                result.fromRight++;
            }
        }

        writeState(collect(leftRoot), collect(rightRoot));
        return result;
    }

    private static boolean changed(File file, long time, long size) {
        return file.lastModified() != time || file.length() != size;
    }

    private static Map<String, File> collect(File root) {
        Map<String, File> out = new HashMap<>();
        collect(root, "", out);
        return out;
    }

    private static void collect(File directory, String prefix, Map<String, File> out) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (name.equals(".git") || name.equals(".shard") || name.endsWith(".tmp")) continue;
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (child.isDirectory()) collect(child, path, out);
            else out.put(path, child);
        }
    }

    private static boolean sameContent(File first, File second) throws Exception {
        if (first.length() != second.length()) return false;
        FileInputStream left = new FileInputStream(first);
        FileInputStream right = new FileInputStream(second);
        try {
            byte[] a = new byte[16384];
            byte[] b = new byte[16384];
            int read;
            while ((read = left.read(a)) != -1) {
                if (right.read(b) != read) return false;
                for (int i = 0; i < read; i++) if (a[i] != b[i]) return false;
            }
            return right.read() == -1;
        } finally {
            left.close();
            right.close();
        }
    }

    private static void copy(File from, File to) throws Exception {
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new java.io.IOException("Cannot create folder");
        }
        FileInputStream input = new FileInputStream(from);
        FileOutputStream output = new FileOutputStream(to);
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
        } finally {
            input.close();
            output.close();
        }
        //noinspection ResultOfMethodCallIgnored
        to.setLastModified(from.lastModified());
    }

    private static File conflictFile(File original) {
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new java.util.Date());
        return new File(original.getParentFile(),
                stem + ".conflict-git-" + stamp + ext);
    }

    private Map<String, Stamp> readState() {
        Map<String, Stamp> out = new HashMap<>();
        if (!stateFile.exists()) return out;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(stateFile));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\\t", -1);
                if (p.length != 5) continue;
                Stamp stamp = new Stamp();
                stamp.leftTime = Long.parseLong(p[1]);
                stamp.leftSize = Long.parseLong(p[2]);
                stamp.rightTime = Long.parseLong(p[3]);
                stamp.rightSize = Long.parseLong(p[4]);
                out.put(p[0], stamp);
            }
            reader.close();
        } catch (Throwable ignored) { }
        return out;
    }

    private void writeState(Map<String, File> left, Map<String, File> right) throws Exception {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, File> entry : left.entrySet()) {
            File peer = right.get(entry.getKey());
            if (peer == null) continue;
            File file = entry.getValue();
            text.append(entry.getKey().replace('\t', '_')).append('\t')
                    .append(file.lastModified()).append('\t').append(file.length()).append('\t')
                    .append(peer.lastModified()).append('\t').append(peer.length()).append('\n');
        }
        NoteFile.writeAtomic(stateFile, text.toString());
    }
}
