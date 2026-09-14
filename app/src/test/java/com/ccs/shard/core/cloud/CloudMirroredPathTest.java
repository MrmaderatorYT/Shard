package com.ccs.shard.core.cloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which vault paths the cloud mirror is allowed to carry. */
public class CloudMirroredPathTest {

    @Test public void carriesNotesCanvasesAndLatex() {
        assertTrue(CloudSyncService.mirrored("hello.md"));
        assertTrue(CloudSyncService.mirrored("Work/notes/plan.markdown"));
        assertTrue(CloudSyncService.mirrored("paper.tex"));
        assertTrue(CloudSyncService.mirrored("Boards/roadmap.canvas"));
    }

    @Test public void carriesAttachmentsOfAnyType() {
        assertTrue(CloudSyncService.mirrored("attachments/photo.heic"));
        assertTrue(CloudSyncService.mirrored("attachments/scan.pdf"));
        assertTrue(CloudSyncService.mirrored("attachments/voice.opus"));
    }

    @Test public void neverCarriesTheSidecar() {
        // Version history, trash, and the mirror's own bookkeeping are local
        // by design - and uploading the index would re-trigger the write hook.
        assertFalse(CloudSyncService.mirrored(".shard/index.tsv"));
        assertFalse(CloudSyncService.mirrored(".shard/cloud-index.tsv"));
        assertFalse(CloudSyncService.mirrored(".shard/cloud-queue.tsv"));
        assertFalse(CloudSyncService.mirrored(".shard/versions/hello.md"));
        assertFalse(CloudSyncService.mirrored(".shard/trash/123__hello.md"));
    }

    @Test public void ignoresHiddenAndTemporaryFiles() {
        assertFalse(CloudSyncService.mirrored(".hidden.md"));
        assertFalse(CloudSyncService.mirrored("Work/.hello.md.tmp"));
        assertFalse(CloudSyncService.mirrored("folder-sync.tsv"));
        assertFalse(CloudSyncService.mirrored(""));
        assertFalse(CloudSyncService.mirrored(null));
    }

    @Test public void ignoresForeignFilesInTheVaultRoot() {
        assertFalse(CloudSyncService.mirrored("random.zip"));
        assertFalse(CloudSyncService.mirrored("Photos/holiday.jpg"));
    }
}
