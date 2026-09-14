package com.ccs.shard;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ccs.shard.canvas.CanvasDoc;
import com.ccs.shard.core.CanvasStore;
import com.ccs.shard.core.Vault;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** On-device round-trip coverage for the persisted canvas format and store. */
@RunWith(AndroidJUnit4.class)
public final class CanvasPersistenceInstrumentedTest {

    @Test
    public void canvasSurvivesWriteReadRenameAndDelete() throws Exception {
        // Instrumentation runs under the target UID. Keep the test vault in a
        // unique cache subdirectory and namespace its preferences so the real
        // configured vault is never read or changed.
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(target.getCacheDir(),
                "canvas-test-" + UUID.randomUUID().toString().substring(0, 8));
        assertTrue(testRoot.mkdirs());
        Context context = new IsolatedTestContext(target, testRoot);
        CanvasStore store = new CanvasStore(new Vault(context));
        String unique = "Canvas test " + UUID.randomUUID().toString().substring(0, 8);
        String id = store.create(unique, "");

        try {
            CanvasDoc document = CanvasDoc.parse(store.read(id));
            CanvasDoc.Node text = document.addTextNode("Persist me", 12f, 24f);
            CanvasDoc.Node file = document.addFileNode("folder/Note.md", 420f, 60f);
            assertTrue(document.toggleEdge(text, file));
            store.write(id, document.toJson());

            CanvasDoc restored = CanvasDoc.parse(store.read(id));
            assertEquals(2, restored.nodes().size());
            assertEquals(1, restored.edges().size());
            assertEquals("Persist me", restored.nodes().get(0).text);
            assertEquals("folder/Note.md", restored.nodes().get(1).file);
            assertNotNull(restored.nodeById(restored.edges().get(0).fromNode));
            assertNotNull(restored.nodeById(restored.edges().get(0).toNode));

            String renamed = store.rename(id, unique + " renamed");
            assertNotNull(renamed);
            assertFalse(store.contains(id));
            assertTrue(store.contains(renamed));
            id = renamed;
        } finally {
            assertTrue(store.delete(id));
            context.getSharedPreferences("shard_vault", Context.MODE_PRIVATE)
                    .edit().clear().commit();
            deleteTree(testRoot);
        }
    }

    @Test
    public void unknownJsonCanvasFieldsRoundTripAndIdsStayUnique() throws Exception {
        String raw = "{\"vendor\":{\"version\":2},\"nodes\":["
                + "{\"id\":\"t2\",\"type\":\"text\",\"x\":0,\"y\":0,"
                + "\"width\":260,\"height\":120,\"text\":\"A\",\"vendorNode\":true},"
                + "{\"id\":\"f3\",\"type\":\"file\",\"x\":300,\"y\":0,"
                + "\"width\":260,\"height\":96,\"file\":\"A.md\"}],"
                + "\"edges\":[{\"id\":\"e4\",\"fromNode\":\"t2\","
                + "\"toNode\":\"f3\",\"vendorEdge\":\"kept\"}]}";

        CanvasDoc document = CanvasDoc.parse(raw);
        CanvasDoc.Node added = document.addTextNode("B", 0, 200);
        assertFalse("t2".equals(added.id));
        assertFalse("f3".equals(added.id));
        assertFalse("e4".equals(added.id));

        JSONObject saved = new JSONObject(document.toJson());
        assertEquals(2, saved.getJSONObject("vendor").getInt("version"));
        assertTrue(saved.getJSONArray("nodes").getJSONObject(0).getBoolean("vendorNode"));
        assertEquals("kept",
                saved.getJSONArray("edges").getJSONObject(0).getString("vendorEdge"));
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Redirects every Vault storage primitive into an isolated cache directory. */
    private static final class IsolatedTestContext extends ContextWrapper {
        private final File root;

        IsolatedTestContext(Context base, File root) {
            super(base);
            this.root = root;
        }

        @Override public Context getApplicationContext() { return this; }

        @Override public File getExternalFilesDir(String type) { return root; }

        @Override public File getFilesDir() { return root; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences(root.getName() + "_" + name, mode);
        }
    }
}
