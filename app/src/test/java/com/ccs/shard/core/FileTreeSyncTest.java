package com.ccs.shard.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class FileTreeSyncTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void firstMeetingKeepsVaultPrimaryAndPreservesRemoteConflict() throws Exception {
        File vault = temporary.newFolder("vault");
        File mirror = temporary.newFolder("mirror");
        File state = new File(temporary.newFolder("state"), "sync.tsv");
        write(new File(vault, "note.md"), "local");
        write(new File(mirror, "note.md"), "remote");

        FileTreeSync.Result result = new FileTreeSync(vault, mirror, state).sync();

        assertEquals("local", read(new File(vault, "note.md")));
        assertEquals("local", read(new File(mirror, "note.md")));
        assertEquals(1, result.conflicts);
        File[] conflicts = vault.listFiles(file -> file.getName().startsWith("note.conflict-git-"));
        assertTrue(conflicts != null && conflicts.length == 1);
        assertEquals("remote", read(conflicts[0]));
    }

    @Test
    public void laterRemoteChangeIsPulledAndGitMetadataIsIgnored() throws Exception {
        File vault = temporary.newFolder("vault");
        File mirror = temporary.newFolder("mirror");
        File state = new File(temporary.newFolder("state"), "sync.tsv");
        File local = new File(vault, "note.md");
        File remote = new File(mirror, "note.md");
        write(local, "same");
        write(remote, "same");
        FileTreeSync sync = new FileTreeSync(vault, mirror, state);
        sync.sync();

        write(remote, "remote update");
        File gitMetadata = new File(mirror, ".git/config");
        assertTrue(gitMetadata.getParentFile().mkdirs());
        write(gitMetadata, "secret metadata");
        FileTreeSync.Result result = sync.sync();

        assertEquals("remote update", read(local));
        assertEquals(1, result.fromRight);
        assertFalse(new File(vault, ".git").exists());
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) assertTrue(parent.mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
