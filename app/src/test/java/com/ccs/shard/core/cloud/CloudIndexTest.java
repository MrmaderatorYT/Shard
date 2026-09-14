package com.ccs.shard.core.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/** The remote-id bookkeeping that keeps a save from creating a duplicate. */
@RunWith(RobolectricTestRunner.class)
public class CloudIndexTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private CloudIndex index() throws Exception {
        return new CloudIndex(new File(folder.newFolder("sidecar"), "cloud-index.tsv"));
    }

    @Test public void roundTripsThroughDisk() throws Exception {
        File file = new File(folder.newFolder(), "cloud-index.tsv");
        CloudIndex first = new CloudIndex(file);
        first.put("Notes/hello.md",
                new CloudEntry(CloudProvider.GOOGLE_DRIVE, "id-1", "rev-1", 1234L, 56L));
        first.save();

        CloudEntry read = new CloudIndex(file).get("Notes/hello.md");
        assertNotNull(read);
        assertEquals(CloudProvider.GOOGLE_DRIVE, read.provider());
        assertEquals("id-1", read.remoteId());
        assertEquals("rev-1", read.rev());
        assertEquals(1234L, read.localModified());
        assertEquals(56L, read.localSize());
    }

    @Test public void survivesTabsAndNewlinesInPaths() throws Exception {
        File file = new File(folder.newFolder(), "cloud-index.tsv");
        String path = "odd\tname\nsecond line.md";
        CloudIndex first = new CloudIndex(file);
        first.put(path, new CloudEntry(CloudProvider.GOOGLE_DRIVE, "id-2", "", 1L, 2L));
        first.save();

        // FileTreeSync's state file replaces tabs and would lose this path;
        // a lost path means an orphaned remote file, so this one must not.
        CloudEntry read = new CloudIndex(file).get(path);
        assertNotNull(read);
        assertEquals("id-2", read.remoteId());
    }

    @Test public void hidesEntriesFromAnotherProvider() throws Exception {
        CloudIndex index = index();
        index.put("a.md", new CloudEntry(CloudProvider.DROPBOX, "dbx-1", "", 1L, 2L));

        assertNull("a Dropbox id must never be handed to Drive",
                index.usable("a.md", CloudProvider.GOOGLE_DRIVE));
        assertNotNull(index.usable("a.md", CloudProvider.DROPBOX));
    }

    @Test public void treatsEmptyRemoteIdAsUnuploaded() throws Exception {
        CloudIndex index = index();
        index.put("a.md", new CloudEntry(CloudProvider.GOOGLE_DRIVE, "", "", 1L, 2L));
        assertNull(index.usable("a.md", CloudProvider.GOOGLE_DRIVE));
    }

    @Test public void retainProviderDropsForeignRows() throws Exception {
        CloudIndex index = index();
        index.put("a.md", new CloudEntry(CloudProvider.GOOGLE_DRIVE, "g", "", 1L, 2L));
        index.put("b.md", new CloudEntry(CloudProvider.DROPBOX, "d", "", 1L, 2L));

        index.retainProvider(CloudProvider.GOOGLE_DRIVE);

        assertEquals(1, index.size());
        assertNotNull(index.get("a.md"));
        assertNull(index.get("b.md"));
    }

    @Test public void unknownFileVersionIsIgnoredRatherThanMisread() throws Exception {
        File file = new File(folder.newFolder(), "cloud-index.tsv");
        com.ccs.shard.core.NoteFile.writeAtomic(file,
                "shard-cloud-index\t99\na.md\tgdrive\tid\trev\t1\t2\n");

        CloudIndex index = new CloudIndex(file);
        assertEquals(0, index.size());
    }

    @Test public void corruptRowsAreSkippedNotFatal() throws Exception {
        File file = new File(folder.newFolder(), "cloud-index.tsv");
        com.ccs.shard.core.NoteFile.writeAtomic(file,
                "shard-cloud-index\t1\n"
                        + "truncated\trow\n"
                        + "good.md\tgdrive\tid-3\trev\t7\t8\n"
                        + "\tgdrive\tid-4\trev\t1\t2\n");

        CloudIndex index = new CloudIndex(file);
        assertEquals(1, index.size());
        assertTrue(index.get("good.md").hasRemoteId());
    }
}
