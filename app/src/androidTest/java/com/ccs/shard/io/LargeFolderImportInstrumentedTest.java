package com.ccs.shard.io;

import android.content.Context;

import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class LargeFolderImportInstrumentedTest {

    @Test public void walksLargeNestedFolderTree() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "large-folder-import-test");
        delete(root);
        if (!root.mkdirs()) throw new IllegalStateException("cannot create test folder");
        int expected = 1200;
        for (int i = 0; i < expected; i++) {
            File folder = new File(root, "folder-" + (i / 100));
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException();
            FileOutputStream out = new FileOutputStream(new File(folder, "Note " + i + ".md"));
            out.write(("# " + i).getBytes("UTF-8"));
            out.close();
        }
        assertEquals(expected, Importer.countImportableTreeForTest(DocumentFile.fromFile(root)));
        delete(root);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
