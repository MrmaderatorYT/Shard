package com.ccs.shard.io;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EnvironmentBackupTest {

    @Test public void fullEnvironmentZipIncludesPortableAndRecoveryState() throws Exception {
        File base = Files.createTempDirectory("shard-environment-backup").toFile();
        File root = new File(base, "vault");
        File target = new File(base, "backup.zip");
        try {
            write(new File(root, "Folder/Note.md"), "# Note");
            write(new File(root, "Board.canvas"), "{\"nodes\":[]}");
            write(new File(root, "attachments/image.txt"), "image");
            write(new File(root, ".shard/versions/Note/1.md"), "old");
            write(new File(root, ".shard/trash/Deleted.md"), "deleted");
            write(new File(root, "ignored.partial"), "incomplete");

            Exporter.zipEnvironmentForTest(root, target);
            assertTrue(target.isFile());
            assertFalse(new File(base, "backup.zip.partial").exists());

            Set<String> entries = new HashSet<>();
            ZipFile zip = new ZipFile(target);
            try {
                java.util.Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) entries.add(all.nextElement().getName());
                assertEquals("# Note", read(zip.getInputStream(
                        zip.getEntry("Folder/Note.md"))));
            } finally {
                zip.close();
            }
            assertTrue(entries.contains("Board.canvas"));
            assertTrue(entries.contains("attachments/image.txt"));
            assertTrue(entries.contains(".shard/versions/Note/1.md"));
            assertTrue(entries.contains(".shard/trash/Deleted.md"));
            assertFalse(entries.contains("ignored.partial"));
        } finally {
            delete(base);
        }
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create fixture folder");
        }
        FileOutputStream output = new FileOutputStream(file);
        try { output.write(value.getBytes(StandardCharsets.UTF_8)); }
        finally { output.close(); }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
