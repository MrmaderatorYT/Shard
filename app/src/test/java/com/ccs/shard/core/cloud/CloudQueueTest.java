package com.ccs.shard.core.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.List;

/** The pending-upload journal: collapsing, durability, and the mid-upload race. */
@RunWith(RobolectricTestRunner.class)
public class CloudQueueTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private CloudQueue queue() throws Exception {
        return new CloudQueue(new File(folder.newFolder(), "cloud-queue.tsv"));
    }

    @Test public void repeatedSavesCollapseToOneUpload() throws Exception {
        CloudQueue queue = queue();
        for (int i = 0; i < 50; i++) queue.upsert("a.md");
        assertEquals(1, queue.size());
    }

    @Test public void deleteReplacesPendingUpload() throws Exception {
        CloudQueue queue = queue();
        queue.upsert("a.md");
        queue.delete("a.md");

        List<CloudQueue.Op> pending = queue.pending();
        assertEquals(1, pending.size());
        assertEquals(CloudOpKind.DELETE, pending.get(0).kind);
    }

    @Test public void recreatingAFileCancelsItsDelete() throws Exception {
        CloudQueue queue = queue();
        queue.delete("a.md");
        queue.upsert("a.md");

        assertEquals(CloudOpKind.UPSERT, queue.pending().get(0).kind);
    }

    @Test public void oldestEditUploadsFirst() throws Exception {
        CloudQueue queue = queue();
        queue.upsert("first.md");
        queue.upsert("second.md");
        // Re-saving must not push the older entry back in the line.
        queue.upsert("second.md");
        queue.upsert("first.md");

        List<CloudQueue.Op> pending = queue.pending();
        assertEquals("first.md", pending.get(0).path);
        assertEquals("second.md", pending.get(1).path);
    }

    @Test public void saveDuringUploadIsNotAcknowledgedAway() throws Exception {
        CloudQueue queue = queue();
        queue.upsert("a.md");
        CloudQueue.Op inFlight = queue.pending().get(0);

        // The user saves again while the upload is on the wire.
        queue.upsert("a.md");
        // The drain finishes and clears the operation it started with.
        queue.done(inFlight);

        assertEquals("the newer edit must still be queued", 1, queue.size());
    }

    @Test public void doneRemovesTheOperationItStartedWith() throws Exception {
        CloudQueue queue = queue();
        queue.upsert("a.md");
        queue.done(queue.pending().get(0));
        assertTrue(queue.isEmpty());
    }

    @Test public void survivesProcessRestartAfterFlush() throws Exception {
        File file = new File(folder.newFolder(), "cloud-queue.tsv");
        CloudQueue first = new CloudQueue(file);
        first.upsert("kept\tpath.md");
        first.delete("gone.md");
        first.flush();

        CloudQueue reloaded = new CloudQueue(file);
        assertEquals(2, reloaded.size());
        assertEquals(CloudOpKind.UPSERT, reloaded.pending().get(0).kind);
        assertEquals("kept\tpath.md", reloaded.pending().get(0).path);
        assertEquals(CloudOpKind.DELETE, reloaded.pending().get(1).kind);
    }

    @Test public void poisonEntryIsDroppedAfterEnoughFailures() throws Exception {
        CloudQueue queue = queue();
        queue.upsert("bad.md");
        for (int i = 0; i < 5; i++) {
            queue.failed(queue.pending().get(0));
        }
        queue.dropExhausted(5);
        assertTrue("one bad path must not wedge the queue", queue.isEmpty());
    }

    @Test public void attemptsSurviveARequeueOfTheSameKind() throws Exception {
        CloudQueue queue = queue();
        queue.upsert("bad.md");
        queue.failed(queue.pending().get(0));
        queue.upsert("bad.md");
        assertEquals(1, queue.pending().get(0).attempts);
    }

    @Test public void clearForgetsEverythingOnDiskToo() throws Exception {
        File file = new File(folder.newFolder(), "cloud-queue.tsv");
        CloudQueue queue = new CloudQueue(file);
        queue.upsert("a.md");
        queue.flush();
        queue.clear();

        assertTrue(queue.isEmpty());
        assertEquals(0, new CloudQueue(file).size());
    }
}
