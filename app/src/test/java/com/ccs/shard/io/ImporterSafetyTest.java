package com.ccs.shard.io;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ImporterSafetyTest {

    @Test public void previewsLargeZipWithoutExtractingIt() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        for (int i = 0; i < 3000; i++) {
            zip.putNextEntry(new ZipEntry("folder-" + (i / 100) + "/Note " + i + ".md"));
            zip.write(("# Note " + i + "\nbody").getBytes("UTF-8"));
            zip.closeEntry();
        }
        zip.putNextEntry(new ZipEntry("Board.canvas"));
        zip.write("{\"nodes\":[],\"edges\":[]}".getBytes("UTF-8"));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("attachments/picture.png"));
        zip.write(new byte[]{1, 2, 3});
        zip.closeEntry();
        zip.finish();
        zip.close();

        Importer.Preview preview = Importer.inspectZip(
                new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(3000, preview.notes);
        assertEquals(1, preview.canvases);
        assertEquals(1, preview.attachments);
        assertTrue(preview.unpackedBytes > 0);
    }

    @Test public void boundedReaderRejectsOversizedEntry() {
        byte[] large = new byte[4097];
        assertNull(Importer.readAll(new ByteArrayInputStream(large), true, 4096));
    }

    @Test public void zipSlipPathIsRejected() {
        assertNull(Importer.sanitizeZipPath("../../outside.md"));
        assertEquals("folder/Note.md", Importer.sanitizeZipPath("folder/Note.md"));
    }
}
