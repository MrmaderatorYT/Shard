package com.ccs.shard.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GitSyncConfigTest {

    @Test
    public void acceptsHttpsAndAppliesSafeDefaults() {
        GitSyncConfig config = new GitSyncConfig(
                " https://github.com/example/notes.git ", "", "", "", "");

        assertTrue(config.isValid());
        assertEquals("main", config.getBranch());
        assertEquals("Shard", config.getAuthorName());
        assertEquals("shard@localhost", config.getAuthorEmail());
    }

    @Test
    public void rejectsEmbeddedCredentialsSshAndInvalidBranches() {
        assertFalse(config("https://user:secret@example.com/notes.git", "main").isValid());
        assertFalse(config("http://example.com/notes.git", "main").isValid());
        assertFalse(config("git@example.com:notes.git", "main").isValid());
        assertFalse(config("https://example.com/notes.git", "feature..broken").isValid());
    }

    private static GitSyncConfig config(String url, String branch) {
        return new GitSyncConfig(url, branch, "user", "Author", "author@example.com");
    }
}
