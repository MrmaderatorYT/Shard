package com.ccs.shard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ccs.shard.core.Note;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;

import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** On-device coverage for the editor interactions most likely to regress. */
@RunWith(AndroidJUnit4.class)
public final class EditorInteractionsInstrumentedTest {

    @Test public void listMarkersAlignWithFirstTextLine() throws Exception {
        VaultRepository repository = readyRepository();
        ShardApp app = (ShardApp) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        Prefs prefs = app.prefs();
        int previousFont = prefs.fontSize();
        Note note = create(repository, "1. Rered\n\n- Dddd");
        ActivityScenario<NoteEditorActivity> scenario = launch(note);
        try {
            for (int fontSize : new int[]{16, 26}) {
                prefs.setFontSize(fontSize);
                scenario.recreate();
                for (int position = 1; position <= 2; position++) {
                    awaitBlockLayout(scenario, position);
                    final int itemPosition = position;
                    scenario.onActivity(activity -> {
                        RecyclerView list = activity.findViewById(R.id.blockList);
                        View row = list.findViewHolderForAdapterPosition(itemPosition).itemView;
                        EditText input = row.findViewById(R.id.block_input);
                        // Use wrapping text: pasting newlines intentionally creates new blocks.
                        input.setText("Rered Dddd second line Rered Dddd second line "
                                + "Rered Dddd second line Rered Dddd second line "
                                + "Rered Dddd second line Rered Dddd second line "
                                + "Rered Dddd second line Rered Dddd second line");
                    });
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    // Wait for a frame after changing the text height.
                    SystemClock.sleep(100);
                    scenario.onActivity(activity -> {
                        RecyclerView list = activity.findViewById(R.id.blockList);
                        View row = list.findViewHolderForAdapterPosition(itemPosition).itemView;
                        EditText input = row.findViewById(R.id.block_input);
                        ViewGroup leading = row.findViewById(R.id.block_leading);
                        assertEquals(1, leading.getChildCount());
                        TextView marker = (TextView) leading.getChildAt(0);
                        int markerBaseline = leading.getTop() + marker.getTop() + marker.getBaseline();
                        int textBaseline = input.getTop() + input.getBaseline();
                        assertEquals("list marker baseline at font size " + fontSize,
                                textBaseline, markerBaseline);
                        assertTrue("must cover multiline text", input.getLineCount() >= 2);
                    });
                }
            }
        } finally {
            scenario.close();
            prefs.setFontSize(previousFont);
            cleanup(repository, note);
        }
    }

    @Test public void newNoteKeepsIdentityAndTextAfterRecreation() throws Exception {
        VaultRepository repository = readyRepository();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_INITIAL_TITLE,
                "Rotation test " + UUID.randomUUID().toString().substring(0, 8));
        ActivityScenario<NoteEditorActivity> scenario = ActivityScenario.launch(intent);
        final String[] noteId = new String[1];
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            awaitBlockLayout(scenario, 1);
            scenario.onActivity(activity -> {
                noteId[0] = activity.getIntent().getStringExtra(NoteEditorActivity.EXTRA_NOTE_ID);
                assertNotNull(noteId[0]);
                RecyclerView list = activity.findViewById(R.id.blockList);
                blockInput(list, 1).setText("Text entered immediately before rotation");
            });
            for (int rotation = 0; rotation < 2; rotation++) {
                scenario.recreate();
                // The repository loads the saved body on its serial disk executor.
                CountDownLatch loaded = new CountDownLatch(1);
                com.ccs.shard.core.Io.onDisk(() ->
                        com.ccs.shard.core.Io.onMain(loaded::countDown));
                assertTrue(loaded.await(15, TimeUnit.SECONDS));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                awaitBlockLayout(scenario, 1);
                scenario.onActivity(activity -> {
                    assertEquals(noteId[0], activity.getIntent()
                            .getStringExtra(NoteEditorActivity.EXTRA_NOTE_ID));
                    RecyclerView list = activity.findViewById(R.id.blockList);
                    assertEquals("Text entered immediately before rotation",
                            blockInput(list, 1).getText().toString());
                });
            }
            String renamedTitle = "Renamed rotation test " + UUID.randomUUID().toString().substring(0, 8);
            awaitBlockLayout(scenario, 0);
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.blockList);
                com.ccs.shard.ui.NoteHeaderView header = (com.ccs.shard.ui.NoteHeaderView)
                        list.findViewHolderForAdapterPosition(0).itemView;
                header.titleInput().setText(renamedTitle);
            });
            awaitBlockLayout(scenario, 1);
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.blockList);
                blockInput(list, 1).setText("Body edited together with the title");
            });
            scenario.recreate();
            noteId[0] = renamedTitle + ".md";
            CountDownLatch renamed = new CountDownLatch(1);
            com.ccs.shard.core.Io.onDisk(() ->
                    com.ccs.shard.core.Io.onMain(renamed::countDown));
            assertTrue(renamed.await(15, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            awaitBlockLayout(scenario, 1);
            scenario.onActivity(activity -> {
                assertEquals(noteId[0], activity.getIntent()
                        .getStringExtra(NoteEditorActivity.EXTRA_NOTE_ID));
                RecyclerView list = activity.findViewById(R.id.blockList);
                assertEquals("Body edited together with the title",
                        blockInput(list, 1).getText().toString());
            });
        } finally {
            scenario.close();
            if (noteId[0] != null) cleanup(repository, repository.meta(noteId[0]));
        }
    }

    @Test public void enterSplitsBlockButShiftEnterKeepsSoftLineBreak() throws Exception {
        VaultRepository repository = readyRepository();
        Note note = create(repository, "first");
        ActivityScenario<NoteEditorActivity> scenario = launch(note);
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            final int[] itemCountBefore = new int[1];
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.blockList);
                itemCountBefore[0] = list.getAdapter().getItemCount();
                EditText input = blockInput(list, 1);
                assertEquals("first", input.getText().toString());
                input.requestFocus();
                input.setSelection(input.length());
                sendKey(input, KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON);
                assertEquals("first\n", input.getText().toString());
                sendKey(input, KeyEvent.KEYCODE_ENTER, 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.blockList);
                assertEquals(itemCountBefore[0] + 1, list.getAdapter().getItemCount());
                assertEquals("first\n", blockInput(list, 1).getText().toString());
                assertEquals("", blockInput(list, 2).getText().toString());
            });
        } finally {
            scenario.close();
            cleanup(repository, note);
        }
    }

    @Test public void longPressDragHandleReordersVisibleBlocks() throws Exception {
        VaultRepository repository = readyRepository();
        Note note = create(repository, "One\n\nTwo\n\nThree");
        ActivityScenario<NoteEditorActivity> scenario = launch(note);
        try {
            onView(withId(R.id.blockList)).check(matches(isDisplayed()))
                    .perform(dragBlock(1, 3));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.blockList);
                String first = blockInput(list, 1).getText().toString();
                String second = blockInput(list, 2).getText().toString();
                String third = blockInput(list, 3).getText().toString();
                assertFalse("One".equals(first));
                assertTrue("One".equals(second) || "One".equals(third));
            });
        } finally {
            scenario.close();
            cleanup(repository, note);
        }
    }

    @Test public void rawMarkdownModePreservesSourceAndAppliesHighlighting() throws Exception {
        VaultRepository repository = readyRepository();
        String markdown = "# Heading\n\n```java\nint value = 3;\n```";
        Note note = create(repository, markdown);
        ActivityScenario<NoteEditorActivity> scenario = launch(note);
        try {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            onView(withId(R.id.btnMore)).perform(click());
            onView(withText(context.getString(R.string.raw_markdown))).perform(click());
            onView(withId(R.id.rawMarkdown)).check(matches(allOf(isDisplayed(), withText(markdown))));
            scenario.onActivity(activity -> {
                EditText raw = activity.findViewById(R.id.rawMarkdown);
                assertTrue(raw.getText() instanceof Spanned);
                ForegroundColorSpan[] spans = ((Spanned) raw.getText()).getSpans(
                        0, raw.length(), ForegroundColorSpan.class);
                assertTrue(spans.length > 0);
            });

            // The same menu action returns to blocks without changing source text.
            onView(withId(R.id.btnMore)).perform(click());
            onView(withText(context.getString(R.string.raw_markdown))).perform(click());
            onView(withId(R.id.blockList)).check(matches(isDisplayed()));
        } finally {
            scenario.close();
            cleanup(repository, note);
        }
    }

    @Test public void darkThemeLargeEditorFontAndTalkBackLabelsRemainUsable() throws Exception {
        VaultRepository repository = readyRepository();
        ShardApp app = (ShardApp) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        Prefs prefs = app.prefs();
        int previousTheme = prefs.themeMode();
        int previousFont = prefs.fontSize();
        boolean previousDynamic = prefs.dynamicColor();
        Note note = create(repository, "Accessible text");
        prefs.setDynamicColor(false);
        prefs.setThemeMode(Prefs.THEME_DARK);
        prefs.setFontSize(26);
        ActivityScenario<NoteEditorActivity> scenario = launch(note);
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                android.util.TypedValue color = new android.util.TypedValue();
                assertTrue(activity.getTheme().resolveAttribute(
                        com.google.android.material.R.attr.colorSurface, color, true));
                int surface = color.resourceId != 0
                        ? androidx.core.content.ContextCompat.getColor(activity, color.resourceId)
                        : color.data;
                assertTrue(Color.alpha(surface) > 0);
                assertTrue(ColorUtils.calculateLuminance(surface) < 0.25d);

                RecyclerView list = activity.findViewById(R.id.blockList);
                EditText input = blockInput(list, 1);
                float expected = 25f * activity.getResources().getDisplayMetrics().scaledDensity;
                assertTrue(input.getTextSize() >= expected);
                assertTalkBackLabels(activity.findViewById(android.R.id.content));
            });
        } finally {
            scenario.close();
            prefs.setThemeMode(previousTheme);
            prefs.setFontSize(previousFont);
            prefs.setDynamicColor(previousDynamic);
            cleanup(repository, note);
        }
    }

    private static void awaitBlockLayout(ActivityScenario<NoteEditorActivity> scenario, int position) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        boolean[] laidOut = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.blockList);
                list.scrollToPosition(position);
                laidOut[0] = list.findViewHolderForAdapterPosition(position) != null;
            });
            if (laidOut[0]) return;
            SystemClock.sleep(50);
        }
        assertTrue("block " + position + " did not finish layout", laidOut[0]);
    }

    private static ActivityScenario<NoteEditorActivity> launch(Note note) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.getId());
        return ActivityScenario.launch(intent);
    }

    private static Note create(VaultRepository repository, String content) {
        String title = "Shard UI test " + UUID.randomUUID().toString().substring(0, 8);
        Note note = repository.createNote(title, "", content);
        assertNotNull(note);
        return note;
    }

    private static VaultRepository readyRepository() throws Exception {
        ShardApp app = (ShardApp) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        VaultRepository repository = app.repository();
        CountDownLatch ready = new CountDownLatch(1);
        repository.open(ready::countDown);
        assertTrue("repository did not open", ready.await(15, TimeUnit.SECONDS));
        return repository;
    }

    private static EditText blockInput(RecyclerView list, int globalPosition) {
        list.scrollToPosition(globalPosition);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(globalPosition);
        assertNotNull("block holder " + globalPosition + " is not laid out", holder);
        EditText input = holder.itemView.findViewById(R.id.block_input);
        assertNotNull("position is not a text block", input);
        return input;
    }

    private static void sendKey(EditText input, int keyCode, int metaState) {
        long now = SystemClock.uptimeMillis();
        input.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                keyCode, 0, metaState));
        input.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                keyCode, 0, metaState));
    }

    private static ViewAction dragBlock(final int fromGlobalPosition,
                                        final int toGlobalPosition) {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints() { return isDisplayed(); }

            @Override public String getDescription() {
                return "long-press a block handle and drag it to another block";
            }

            @Override public void perform(UiController ui, View view) {
                RecyclerView list = (RecyclerView) view;
                RecyclerView.ViewHolder from = list.findViewHolderForAdapterPosition(
                        fromGlobalPosition);
                RecyclerView.ViewHolder to = list.findViewHolderForAdapterPosition(
                        toGlobalPosition);
                assertNotNull(from);
                assertNotNull(to);
                View handle = from.itemView.findViewById(R.id.block_handle);
                int[] handleLocation = new int[2];
                int[] targetLocation = new int[2];
                handle.getLocationOnScreen(handleLocation);
                to.itemView.getLocationOnScreen(targetLocation);
                float startX = handleLocation[0] + handle.getWidth() / 2f;
                float startY = handleLocation[1] + handle.getHeight() / 2f;
                float endX = startX;
                float endY = targetLocation[1] + to.itemView.getHeight() / 2f;
                long down = SystemClock.uptimeMillis();
                inject(ui, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN,
                        startX, startY, 0));
                ui.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout() + 120L);
                for (int step = 1; step <= 8; step++) {
                    float fraction = step / 8f;
                    inject(ui, MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_MOVE, startX + (endX - startX) * fraction,
                            startY + (endY - startY) * fraction, 0));
                    ui.loopMainThreadForAtLeast(24L);
                }
                inject(ui, MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP, endX, endY, 0));
                ui.loopMainThreadUntilIdle();
            }
        };
    }

    private static void inject(UiController ui, MotionEvent event) {
        try { assertTrue(ui.injectMotionEvent(event)); }
        catch (androidx.test.espresso.InjectEventSecurityException error) {
            throw new AssertionError("cannot inject drag gesture", error);
        }
        finally { event.recycle(); }
    }

    private static void assertTalkBackLabels(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.isClickable()) {
            CharSequence description = view.getContentDescription();
            CharSequence text = view instanceof TextView ? ((TextView) view).getText() : null;
            assertTrue("clickable view lacks a TalkBack label: " + view,
                    (description != null && description.length() > 0)
                            || (text != null && text.length() > 0));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertTalkBackLabels(group.getChildAt(i));
            }
        }
    }

    private static void cleanup(VaultRepository repository, Note note) {
        try {
            repository.delete(note);
            File[] trash = repository.vault().trashDir().listFiles();
            if (trash != null) {
                String stem = note.fileName().replace(".md", "");
                for (File file : trash) {
                    if (file.getName().contains(stem)) delete(file);
                }
            }
            repository.refresh();
        } catch (Throwable ignored) { }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
