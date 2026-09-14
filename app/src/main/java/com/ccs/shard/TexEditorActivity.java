package com.ccs.shard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.TexLinter;
import com.ccs.shard.editor.TexEditingController;
import com.ccs.shard.editor.TexPreview;
import com.ccs.shard.io.Exporter;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.SplitPane;
import com.ccs.shard.ui.SymbolBar;
import com.ccs.shard.ui.TexEditText;
import com.ccs.shard.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/** Raw UTF-8 source editor for LaTeX notes; no Markdown normalisation is applied. */
public final class TexEditorActivity extends BaseActivity {

    private static final String EXTRA_NOTE_ID = "tex_note_id";
    private static final String EXTRA_CREATE_IN_FOLDER = "tex_create_in_folder";
    private static final int[] ZOOM_LEVELS = {85, 100, 120, 140};

    private TexEditText source;
    private TexEditingController editor;
    private TextView path;
    private TextView tabTitle;
    private TextView saveStatus;
    private TextView problemsLabel;
    private TextView previewText;
    private TextView statusText;
    private android.widget.ImageView statusIcon;
    private TextView pageCount;
    private TextView zoomBadge;
    private View floatingPdfAction;
    private SplitPane split;
    private SymbolBar symbols;
    private TexPreview preview;
    /** The last live-preview job; cancelled when newer source/layout arrives. */
    private Future<?> previewTask;
    /** Generation guard so a slow old render can never replace newer text. */
    private int previewRequestId;
    private int zoomIndex = 1;
    private final Runnable renderPreview = new Runnable() {
        @Override public void run() { requestPreviewRender(); }
    };
    private Note note;
    private boolean dirty;
    private Exporter.Result pendingExport;
    private ActivityResultLauncher<Intent> saveFilePicker;

    private final Runnable autosave = new Runnable() {
        @Override public void run() { commit(false); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_NOTE_ID)) {
            getIntent().putExtra(EXTRA_NOTE_ID, savedInstanceState.getString(EXTRA_NOTE_ID));
        }
        if (getIntent().getStringExtra(EXTRA_NOTE_ID) == null
                && (repo.vault().isSharedStorage()
                && !repo.vault().hasFullFileAccess())) {
            Intent home = new Intent(this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(home);
            finish();
            return;
        }
        setContentView(R.layout.activity_tex_editor);

        source = findViewById(R.id.texSource);
        path = findViewById(R.id.texPath);
        tabTitle = findViewById(R.id.texTabTitle);
        saveStatus = findViewById(R.id.texSaveStatus);
        problemsLabel = findViewById(R.id.texProblems);
        previewText = findViewById(R.id.texPreview);
        statusText = findViewById(R.id.texStatusText);
        statusIcon = findViewById(R.id.texStatusIcon);
        pageCount = findViewById(R.id.texPageCount);
        zoomBadge = findViewById(R.id.texZoomBadge);
        floatingPdfAction = findViewById(R.id.texFloatingPdfAction);
        if (previewText != null) {
            previewText.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft && right > left) {
                    // Divider drags can generate dozens of layout passes.  Do
                    // not parse/layout TeX for every one; render only after
                    // the width has been stable for a short pause.
                    if (split == null || !split.isDragging()) {
                        schedulePreview(180L);
                    }
                }
            });
        }
        split = findViewById(R.id.texSplit);
        symbols = findViewById(R.id.texSymbols);
        source.setTypeface(Typeface.MONOSPACE);
        source.setTextSize(prefs.fontSize());
        source.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // High-quality hyphenation/balanced breaking is disproportionately
            // expensive for a long TeX source and a span-heavy PDF preview.
            // Simple breaking keeps the editor and the one-shot post-resize
            // layout responsive without changing the exported PDF.
            setSimpleBreakStrategy(source);
            source.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            if (previewText != null) {
                setSimpleBreakStrategy(previewText);
                previewText.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            }
        }
        editor = new TexEditingController(this, source, new TexEditingController.Listener() {
            @Override public void onTextEdited() { onSourceEdited(); }

            @Override public void onProblemsFound(List<TexLinter.Problem> problems) {
                showProblems(problems);
            }
        });

        symbols.setTarget(source);
        symbols.setOnInsert(this::onSourceEdited);

        preview = new TexPreview(previewPalette());
        split.setRatio(prefs.texSplitRatio());
        split.setOnRatioChanged(prefs::setTexSplitRatio);
        split.setOnDragStateChanged(dragging -> {
            if (dragging) {
                // Any pending typing/resize render is obsolete while the
                // divider is moving; the panes keep their old widths until
                // release, so no large TextView has to be remeasured here.
                cancelPreviewRender();
            } else {
                // Let SplitPane's final requestLayout() settle before sampling
                // the new width and starting one post-drag render.
                schedulePreview(220L);
            }
        });
        setPreviewVisible(prefs.texPreviewOpen());

        findViewById(R.id.btnBack).setOnClickListener(v -> finishAfterSave());
        findViewById(R.id.btnMore).setOnClickListener(this::showMenu);
        findViewById(R.id.btnTexPreview).setOnClickListener(
                v -> setPreviewVisible(!split.isSecondVisible()));

        if (zoomBadge != null) {
            zoomBadge.setOnClickListener(v -> cycleZoom());
        }
        View quickPdf = findViewById(R.id.btnQuickPdfExport);
        if (quickPdf != null) {
            quickPdf.setOnClickListener(v -> exportPdf(false));
        }
        if (floatingPdfAction != null) {
            floatingPdfAction.setOnClickListener(v -> exportPdf(true));
        }

        saveFilePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri target = result.getData().getData();
                    if (target == null || pendingExport == null) return;
                    final Exporter.Result export = pendingExport;
                    pendingExport = null;
                    Io.onDisk(() -> {
                        final boolean ok = com.ccs.shard.io.Importer
                                .copyToUri(TexEditorActivity.this, export.file, target);
                        Io.onMain(() -> toast(ok ? R.string.exported_ok : R.string.export_failed));
                    });
                });

        loadNote();
    }

    private void cycleZoom() {
        zoomIndex = (zoomIndex + 1) % ZOOM_LEVELS.length;
        int zoomPercent = ZOOM_LEVELS[zoomIndex];
        if (zoomBadge != null) {
            zoomBadge.setText(zoomPercent + "%");
        }
        if (previewText != null) {
            previewText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    14.5f * (zoomPercent / 100f));
        }
    }

    private void onSourceEdited() {
        if (note == null) return;
        dirty = true;
        showStatus(R.string.saving, true);
        Io.cancelMain(autosave);
        Io.onMainDelayed(autosave, prefs.autosaveDelayMs());
        schedulePreview();
    }

    // ---------------------------------------------------------------- preview

    private TexPreview.Palette previewPalette() {
        TexPreview.Palette palette = new TexPreview.Palette();
        // Authentic PDF paper appearance: black serif ink on paper
        palette.text = 0xFF111111;
        palette.muted = 0xFF666666;
        palette.accent = 0xFF0B57D0;
        palette.mathBackground = 0x0A000000;
        return palette;
    }

    private void setPreviewVisible(boolean visible) {
        split.setSecondVisible(visible);
        prefs.setTexPreviewOpen(visible);
        ((android.widget.ImageButton) findViewById(R.id.btnTexPreview)).setColorFilter(
                Ui.themeColor(this, visible
                        ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOnSurfaceVariant));
        findViewById(R.id.btnTexPreview).setContentDescription(
                getString(visible ? R.string.preview_hide : R.string.preview_toggle));
        if (visible) {
            refreshPreview();
        } else {
            cancelPreviewRender();
        }
    }

    /** Re-rendering on every keystroke is wasted work; wait for a pause. */
    private void schedulePreview() {
        schedulePreview(350L);
    }

    private void schedulePreview(long delayMs) {
        if (!split.isSecondVisible()) return;
        // Invalidate a result that is already computing as soon as the source
        // or pane width changes.  Otherwise an old render could briefly land
        // during the debounce window and show stale text.
        invalidatePreviewResult();
        if (statusText != null) {
            statusText.setText(R.string.tex_compiling);
        }
        if (statusIcon != null) {
            statusIcon.setImageResource(R.drawable.ic_recent);
            statusIcon.setColorFilter(0xFFFF9800);
        }
        previewText.removeCallbacks(renderPreview);
        previewText.postDelayed(renderPreview, Math.max(0L, delayMs));
    }

    /**
     * Requests a live render without doing any expensive TeX parsing or
     * StaticLayout work on the UI thread.  Only the latest request is allowed
     * to update the TextView, which keeps resize/edit bursts responsive.
     */
    private void refreshPreview() {
        schedulePreview(0L);
    }

    private void requestPreviewRender() {
        if (!split.isSecondVisible() || split.isDragging() || preview == null) return;
        if (previewText == null || source == null) return;

        // bindNote() can run before the first layout pass.  Retry after a
        // frame-sized pause instead of recursively posting work to the main
        // queue while the view has no usable width.
        int width = previewText.getWidth() - previewText.getPaddingLeft()
                - previewText.getPaddingRight();
        if (width <= 0) {
            previewText.postDelayed(renderPreview, 50L);
            return;
        }

        final int requestId = ++previewRequestId;
        final String sourceText = source.getText() == null
                ? "" : source.getText().toString();
        final int liveWidth = Math.max(0, width);
        if (previewTask != null) {
            previewTask.cancel(true);
        }

        previewTask = Io.submitCompute(() -> {
            try {
                if (Thread.currentThread().isInterrupted()) return;
                // TexPreview keeps per-render reference/citation state, so
                // each background request gets an isolated renderer instance.
                TexPreview renderer = new TexPreview(previewPalette());
                renderer.setLiveContentWidth(liveWidth);
                CharSequence rendered = renderer.render(sourceText);
                if (Thread.currentThread().isInterrupted()) return;
                Io.onMain(() -> applyPreviewResult(requestId, rendered, sourceText));
            } catch (Throwable error) {
                Io.onMain(() -> applyPreviewError(requestId, error));
            }
        });
    }

    private void applyPreviewResult(int requestId, CharSequence rendered, String sourceText) {
        if (requestId != previewRequestId || isFinishing()
                || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())
                || !split.isSecondVisible()) {
            return;
        }
        previewTask = null;
        previewText.setText(rendered);

        if (statusText != null) {
            statusText.setText(R.string.tex_compiled);
        }
        if (statusIcon != null) {
            statusIcon.setImageResource(R.drawable.ic_check);
            statusIcon.setColorFilter(0xFF4CAF50);
        }
        if (pageCount != null) {
            int pages = Math.max(1, (int) Math.ceil(sourceText.length() / 1800.0));
            pageCount.setText(getString(R.string.page_indicator, 1, pages));
        }
    }

    private void applyPreviewError(int requestId, Throwable error) {
        if (requestId != previewRequestId || isFinishing()
                || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())
                || !split.isSecondVisible()) {
            return;
        }
        previewTask = null;
        android.util.Log.w("Shard", "live TeX preview failed", error);
        if (statusText != null) {
            statusText.setText(R.string.tex_compiling);
        }
        if (statusIcon != null) {
            statusIcon.setImageResource(R.drawable.ic_recent);
            statusIcon.setColorFilter(0xFFF44336);
        }
    }

    private void cancelPreviewRender() {
        previewRequestId++;
        if (previewTask != null) {
            previewTask.cancel(true);
            previewTask = null;
        }
        if (previewText != null) {
            previewText.removeCallbacks(renderPreview);
        }
    }

    private void invalidatePreviewResult() {
        previewRequestId++;
        if (previewTask != null) {
            previewTask.cancel(true);
            previewTask = null;
        }
    }

    private void jumpTo(TexLinter.Problem problem) {
        CharSequence text = source.getText();
        if (text == null) return;
        int start = Math.max(0, Math.min(problem.start, text.length()));
        int end = Math.max(start, Math.min(problem.end, text.length()));
        source.requestFocus();
        source.setSelection(start, end);
        Ui.showKeyboard(source);
    }

    /** Summarises what the linter found, newest pass wins. */
    private void showProblems(List<TexLinter.Problem> problems) {
        if (problems.isEmpty()) {
            problemsLabel.setVisibility(View.GONE);
            return;
        }
        TexLinter.Problem first = problems.get(0);
        String message = first.argument == null
                ? getString(first.messageRes)
                : getString(first.messageRes, first.argument);
        if (problems.size() > 1) {
            message = message + "  ·  " + getString(R.string.lint_problems, problems.size());
        }
        problemsLabel.setText(message);
        problemsLabel.setVisibility(View.VISIBLE);
        // Tapping the summary puts the caret on the offending characters, which is
        // the only thing anyone wants to do after reading it.
        problemsLabel.setOnClickListener(v -> jumpTo(first));
    }

    private void loadNote() {
        String noteId = getIntent().getStringExtra(EXTRA_NOTE_ID);
        if (noteId == null) {
            String folder = getIntent().getStringExtra(EXTRA_CREATE_IN_FOLDER);
            note = repo.createTexNote(getString(R.string.untitled), folder, "");
            getIntent().putExtra(EXTRA_NOTE_ID, note.getId());
            bindNote(note);
            source.requestFocus();
            Ui.showKeyboard(source);
            return;
        }
        repo.loadNote(noteId, new Io.Result<Note>() {
            @Override public void onReady(Note loaded) {
                note = loaded;
                prefs.pushRecent(loaded.getId());
                prefs.setLastNoteId(loaded.getId());
                bindNote(loaded);
            }

            @Override public void onError(Throwable error) {
                toast(R.string.note_not_found);
                finish();
            }
        });
    }

    private void bindNote(Note value) {
        editor.setText(value.getContent());
        dirty = false;
        refreshPath();
        refreshPreview();
    }

    private void refreshPath() {
        if (note == null) return;
        String folder = note.folder();
        path.setText(folder.isEmpty() ? note.fileName()
                : folder.replace("/", "  ›  ") + "  ›  " + note.fileName());
        if (tabTitle != null) {
            tabTitle.setText(note.fileName());
        }
    }

    private void commit(boolean snapshot) {
        if (note == null || !dirty) return;
        String text = source.getText().toString();
        if (text.equals(note.getContent())) {
            dirty = false;
            showStatus(R.string.saved, false);
            return;
        }
        note.setContent(text);
        repo.save(note, snapshot);
        dirty = false;
        showStatus(R.string.saved, false);
    }

    private void showStatus(int stringRes, boolean pending) {
        saveStatus.animate().cancel();
        saveStatus.setText(stringRes);
        saveStatus.setAlpha(1f);
        if (!pending) {
            saveStatus.animate().alpha(0f).setStartDelay(1400).setDuration(400).start();
        }
    }

    private void showMenu(View anchor) {
        if (note == null) return;
        AnchoredMenu.vertical(this)
                .title(note.fileName())
                .add(7, R.drawable.ic_arrow_right, getString(R.string.indent))
                .add(8, R.drawable.ic_arrow_left, getString(R.string.outdent))
                .divider()
                .add(9, R.drawable.ic_export, getString(R.string.export_as_pdf))
                .add(1, R.drawable.ic_share, getString(R.string.export_share))
                .add(2, R.drawable.ic_export, getString(R.string.export_save_to))
                .add(3, R.drawable.ic_history, getString(R.string.note_history))
                .divider()
                .add(4, R.drawable.ic_block_text, getString(R.string.note_rename))
                .add(5, R.drawable.ic_move, getString(R.string.note_move))
                .add(new AnchoredMenu.Item(6, R.drawable.ic_delete,
                        getString(R.string.note_delete)).destructive())
                .onItem(this::handleMenu)
                .showAt(anchor);
    }

    private void handleMenu(int id) {
        switch (id) {
            case 9: exportPdf(true); break;
            case 1: exportSource(true); break;
            case 2: exportSource(false); break;
            case 3:
                commit(true);
                HistoryActivity.start(this, note.getId());
                break;
            case 4: promptRename(); break;
            case 5: showMoveDialog(); break;
            case 6: confirmDelete(); break;
            case 7: editor.indent(); break;
            case 8: editor.outdent(); break;
            default: break;
        }
    }

    private void exportPdf(final boolean openImmediately) {
        if (note == null) return;
        commit(true);
        new Exporter(repo).exportNote(note, Exporter.Format.PDF,
                new Exporter.Callback() {
                    @Override public void onExported(Exporter.Result result) {
                        if (openImmediately) {
                            viewFile(result.file, result.mimeType);
                        } else {
                            shareFile(result.file, result.mimeType, note.getTitle());
                        }
                    }

                    @Override public void onFailed(Throwable error) {
                        toast(R.string.export_failed);
                    }
                });
    }

    private void exportSource(final boolean share) {
        commit(true);
        new Exporter(repo).exportNote(note, Exporter.Format.MARKDOWN,
                new Exporter.Callback() {
                    @Override public void onExported(Exporter.Result result) {
                        if (share) {
                            shareFile(result.file, result.mimeType, note.getTitle());
                            return;
                        }
                        pendingExport = result;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(result.mimeType);
                        intent.putExtra(Intent.EXTRA_TITLE, result.file.getName());
                        saveFilePicker.launch(intent);
                    }

                    @Override public void onFailed(Throwable error) {
                        toast(R.string.export_failed);
                    }
                });
    }

    private void promptRename() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(note.getTitle());
        input.setSelectAllOnFocus(true);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog().setTitle(R.string.note_rename)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rename, (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty() || title.equals(note.getTitle())) return;
                    commit(true);
                    String newId = repo.rename(note, title, true);
                    if (newId == null) {
                        toast(R.string.rename_conflict);
                        return;
                    }
                    note.setId(newId);
                    note.setTitle(Md.safeFileName(title));
                    getIntent().putExtra(EXTRA_NOTE_ID, newId);
                    refreshPath();
                })
                .show();
    }

    private void showMoveDialog() {
        final List<String> folders = new ArrayList<>();
        folders.add("");
        folders.addAll(repo.index().allFolders());
        CharSequence[] labels = new CharSequence[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = folders.get(i).isEmpty()
                    ? getString(R.string.vault_root) : folders.get(i);
        }
        dialog().setTitle(R.string.note_move)
                .setItems(labels, (dialog, which) -> {
                    commit(true);
                    String newId = repo.move(note, folders.get(which));
                    if (newId != null) {
                        note.setId(newId);
                        getIntent().putExtra(EXTRA_NOTE_ID, newId);
                        refreshPath();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete() {
        Runnable action = () -> {
            dirty = false;
            Io.cancelMain(autosave);
            repo.delete(note);
            prefs.forgetRecent(note.getId());
            toast(R.string.moved_to_trash);
            finish();
        };
        if (!prefs.confirmDelete()) {
            action.run();
            return;
        }
        confirm(R.string.delete_note_q,
                getString(R.string.delete_note_body, note.getTitle(),
                        prefs.trashRetentionDays()),
                R.string.note_delete, action);
    }

    private void finishAfterSave() {
        if (editor != null) editor.stop();
        Io.cancelMain(autosave);
        cancelPreviewRender();
        commit(true);
        Ui.hideKeyboard(source);
        finish();
    }

    @Override
    protected void onDestroy() {
        cancelPreviewRender();
        super.onDestroy();
    }

    @Override
    @android.annotation.SuppressLint("MissingSuperCall")
    public void onBackPressed() {
        if (editor != null && editor.isSuggestionShowing()) {
            editor.dismissSuggestions();
            return;
        }
        finishAfterSave();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (editor != null) editor.start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        commit(true);
        if (note != null) outState.putString(EXTRA_NOTE_ID, note.getId());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (editor != null) editor.stop();
        Io.cancelMain(autosave);
        if (dirty) commit(true);
        else if (note != null) repo.flush();
    }

    /** The framework API predates the currently annotated LineBreaker constants. */
    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
    @android.annotation.SuppressLint("WrongConstant")
    private static void setSimpleBreakStrategy(android.widget.TextView view) {
        // BREAK_STRATEGY_SIMPLE has always been represented by 0.
        view.setBreakStrategy(0);
    }

    public static void open(Context context, String noteId) {
        Intent intent = new Intent(context, TexEditorActivity.class);
        intent.putExtra(EXTRA_NOTE_ID, noteId);
        context.startActivity(intent);
    }

    public static void create(Context context, String folder) {
        Intent intent = new Intent(context, TexEditorActivity.class);
        intent.putExtra(EXTRA_CREATE_IN_FOLDER, folder);
        context.startActivity(intent);
    }
}
