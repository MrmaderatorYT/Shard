package com.ccs.shard;

import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.block.Block;
import com.ccs.shard.block.BlockAdapter;
import com.ccs.shard.block.BlockDocument;
import com.ccs.shard.block.BlockEditText;
import com.ccs.shard.block.BlockType;
import com.ccs.shard.block.DocHistory;
import com.ccs.shard.block.InlineMd;
import com.ccs.shard.block.LinkSpan;
import com.ccs.shard.block.SlashCommand;
import com.ccs.shard.block.SlashMenu;
import com.ccs.shard.editor.BlockActions;
import com.ccs.shard.editor.NoteActions;
import com.ccs.shard.editor.InlineSuggestions;
import com.ccs.shard.editor.EditorIntents;
import com.ccs.shard.editor.NoteNavigator;
import com.ccs.shard.editor.codeHighliter.HighlightTheme;
import com.ccs.shard.editor.codeHighliter.Markdown;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.io.ExportFlow;
import com.ccs.shard.ui.FormatBar;
import com.ccs.shard.ui.NoteFooterView;
import com.ccs.shard.ui.NoteHeaderView;
import com.ccs.shard.ui.SingleViewAdapter;
import com.ccs.shard.ui.SplitPane;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.Ui;
import com.ccs.shard.util.RelativeTime;
import com.ccs.shard.util.PerformanceMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The note editor.
 *
 * <p>A note is a list of blocks in a {@link RecyclerView}, so only what is on
 * screen exists as views. Edits mutate the block model, which is serialised back
 * to Markdown and written on a debounce — there is no Save button, and closing
 * the screen, backgrounding the app or being killed all leave the file correct.
 *
 * <p>Every contextual action opens beside what it acts on: {@code /} opens the
 * block palette at the caret, a block's handle opens its menu next to the
 * handle, and a table's frame opens table controls at the finger. Nothing here
 * uses a full-screen dialog for an in-document action.
 */
public final class NoteEditorActivity extends BaseActivity
        implements BlockAdapter.Host, SlashMenu.Listener, FormatBar.Listener {

    public static final String EXTRA_NOTE_ID = "note_id";
    public static final String EXTRA_CREATE_IN_FOLDER = "create_in_folder";
    public static final String EXTRA_INITIAL_TITLE = "initial_title";
    public static final String EXTRA_READ_MODE = "read_mode";

    private static final int MENU_TURN_INTO = 1;
    private static final int MENU_DUPLICATE = 2;
    private static final int MENU_MOVE_UP = 3;
    private static final int MENU_MOVE_DOWN = 4;
    private static final int MENU_COPY = 5;
    private static final int MENU_DELETE = 6;
    private static final int MENU_DUE_DATE = 7;

    private RecyclerView blockList;
    private RecyclerView previewList;
    private SplitPane editorSplit;
    private BlockAdapter previewAdapter;
    private TextView breadcrumb;
    private View secondaryPaneBar;
    private TextView secondaryPaneTitle;
    private TextView saveStatus;
    private ImageButton readModeButton;
    private ImageButton lockEditingButton;
    private ImageButton historyBackButton;
    private ImageButton historyForwardButton;
    private EditText rawMarkdown;
    private FrameLayout formatBarHolder;
    private View formatBarDivider;
    private FormatBar formatBar;

    private NoteHeaderView headerView;
    private NoteFooterView footerView;
    private SingleViewAdapter headerAdapter;
    private SingleViewAdapter footerAdapter;
    private BlockAdapter blockAdapter;
    private ItemTouchHelper blockTouchHelper;

    private Note note;
    private BlockDocument document;
    private NoteNavigator noteNavigator;
    /** Optional read-only document rendered beside the current note. */
    private String secondaryNoteId;
    private BlockDocument secondaryDocument;
    private InlineMd secondaryInline;
    private InlineMd inline;
    private Markdown rawHighlighter;
    private final DocHistory history = new DocHistory();
    private ExportFlow exportFlow;
    private BlockActions blockActions;
    private NoteActions noteActions;
    private InlineSuggestions inlineSuggestions;
    private EditorIntents editorIntents;
    private SlashMenu slashMenu;
    private BlockEditText slashField;

    private boolean readMode;
    private boolean rawMode;
    private boolean navigatingHistory;
    private boolean editingLocked;
    private boolean bindingRawMarkdown;
    private final Runnable rawHighlightTask = new Runnable() {
        @Override public void run() { highlightRawMarkdown(); }
    };
    private boolean dirty;
    private boolean restoring;
    private boolean refreshPropertiesOnResume;
    private int pendingImageBlock = -1;

    /** Rendering the whole preview per keystroke is wasted work. */
    private static final long PREVIEW_DELAY_MS = 350L;
    private static final int RAW_HIGHLIGHT_LIMIT = 400_000;

    private final Runnable autosave = new Runnable() {
        @Override public void run() { commit(false); }
    };
    private final Runnable previewRefresh = new Runnable() {
        @Override public void run() { refreshPreview(); }
    };

    private ActivityResultLauncher<String> imagePicker;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_NOTE_ID)) {
            getIntent().putExtra(EXTRA_NOTE_ID, savedInstanceState.getString(EXTRA_NOTE_ID));
            getIntent().putExtra(EXTRA_READ_MODE, savedInstanceState.getBoolean(EXTRA_READ_MODE));
        }
        String requestedId = getIntent().getStringExtra(EXTRA_NOTE_ID);
        if (requestedId == null && repo.vault().isSharedStorage()
                && !repo.vault().hasFullFileAccess()) {
            Intent home = new Intent(this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(home);
            finish();
            return;
        }
        if (NoteFile.isTexFile(requestedId)) {
            TexEditorActivity.open(this, requestedId);
            finish();
            return;
        }
        setContentView(R.layout.activity_note_editor);
        noteNavigator = new NoteNavigator(prefs, repo::meta);

        blockList = findViewById(R.id.blockList);
        previewList = findViewById(R.id.previewList);
        editorSplit = findViewById(R.id.editorSplit);
        breadcrumb = findViewById(R.id.breadcrumb);
        secondaryPaneBar = findViewById(R.id.secondaryPaneBar);
        secondaryPaneTitle = findViewById(R.id.secondaryPaneTitle);
        saveStatus = findViewById(R.id.saveStatus);
        readModeButton = findViewById(R.id.btnReadMode);
        lockEditingButton = findViewById(R.id.btnLockEditing);
        historyBackButton = findViewById(R.id.btnHistoryBack);
        historyForwardButton = findViewById(R.id.btnHistoryForward);
        rawMarkdown = findViewById(R.id.rawMarkdown);
        formatBarHolder = findViewById(R.id.formatBarHolder);
        formatBarDivider = findViewById(R.id.formatBarDivider);

        findViewById(R.id.btnBack).setOnClickListener(v -> finishAfterSave());
        historyBackButton.setOnClickListener(v -> navigateHistory(-1));
        historyForwardButton.setOnClickListener(v -> navigateHistory(1));
        historyBackButton.setOnLongClickListener(v -> {
            showOpenedNotes(v);
            return true;
        });
        historyForwardButton.setOnLongClickListener(v -> {
            showOpenedNotes(v);
            return true;
        });
        findViewById(R.id.btnMore).setOnClickListener(this::showNoteMenu);
        readModeButton.setOnClickListener(v -> setReadMode(!readMode));
        lockEditingButton.setOnClickListener(v -> setEditingLocked(!editingLocked));
        rawMarkdown.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (bindingRawMarkdown || !rawMode || note == null) return;
                onRawMarkdownEdited();
                rawMarkdown.removeCallbacks(rawHighlightTask);
                rawMarkdown.postDelayed(rawHighlightTask, 80L);
            }
        });
        findViewById(R.id.btnPreview).setOnClickListener(
                v -> setPreviewVisible(!editorSplit.isSecondVisible()));
        breadcrumb.setOnClickListener(this::showBreadcrumbMenu);
        secondaryPaneTitle.setOnClickListener(v -> {
            if (secondaryNoteId != null) openNote(secondaryNoteId);
        });
        findViewById(R.id.btnCloseSecond).setOnClickListener(v -> clearSecondNote());

        formatBar = new FormatBar(this);
        formatBar.setListener(this);
        formatBar.setOnSymbol(symbol -> {
            if (rawMode) insertRaw(symbol);
            else {
                blockAdapter.insertAtCaret(symbol);
                onDocumentEdited();
            }
        });
        formatBarHolder.addView(formatBar);

        inline = new InlineMd(buildPalette());
        inline.setResolver(target -> repo.index().resolveLink(target) != null);
        rawHighlighter = new Markdown();
        rawHighlighter.setTheme(Ui.isLight(Ui.themeColor(this,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF))
                ? HighlightTheme.NOTION_LIGHT : HighlightTheme.GITHUB_DARK);

        // Registers an activity-result launcher, so it must exist before onStart.
        exportFlow = new ExportFlow(this, repo).beforeExport(() -> commit(true));
        blockActions = new BlockActions(this, new BlockActionsHost());
        noteActions = new NoteActions(this, repo, prefs, new NoteActionsHost());
        registerPickers();
        setupList();
        // Needs the block list as its anchor, so it comes after setupList().
        inlineSuggestions = new InlineSuggestions(this, repo, blockList);
        editorIntents = new EditorIntents(this, repo, prefs, new EditorIntentsHost());
        if (!editorIntents.handle(getIntent())) loadNote();
        observeKeyboard();
    }

    private void registerPickers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImagePicked);
    }

    private InlineMd.Palette buildPalette() {
        InlineMd.Palette palette = new InlineMd.Palette();
        palette.text = Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);
        palette.link = Ui.themeColor(this,
                com.google.android.material.R.attr.colorPrimary, 0xFF2F6FEB);
        palette.linkUnresolved = Ui.withAlpha(palette.link, 0.55f);
        palette.tag = Ui.themeColor(this,
                com.google.android.material.R.attr.colorSecondary, palette.link);
        palette.marker = Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF787066), 0.55f);
        palette.code = Ui.themeColor(this,
                com.google.android.material.R.attr.colorError, 0xFFD64545);
        palette.codeBackground = Ui.withAlpha(palette.text, 0.07f);
        palette.highlight = Ui.withAlpha(0xFFFFC107, 0.32f);
        return palette;
    }

    private void setupList() {
        headerView = new NoteHeaderView(this);
        footerView = new NoteFooterView(this);
        headerAdapter = new SingleViewAdapter(headerView, -1L);
        footerAdapter = new SingleViewAdapter(footerView, -2L);

        document = BlockDocument.parse("");
        inline.setReferenceResolver(document);
        blockAdapter = new BlockAdapter(this, this, document);
        blockAdapter.setBaseTextSize(prefs.fontSize());
        blockAdapter.setMonospaceBody(prefs.fontFamily() == Prefs.FONT_MONO);

        ConcatAdapter.Config config = new ConcatAdapter.Config.Builder()
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.SHARED_STABLE_IDS)
                .build();
        ConcatAdapter concat = new ConcatAdapter(config,
                headerAdapter, blockAdapter, footerAdapter);

        LinearLayoutManager layout = new LinearLayoutManager(this);
        blockList.setLayoutManager(layout);
        blockList.setAdapter(concat);
        blockList.setItemAnimator(null);
        setupBlockDragging();
        // Blocks are cheap but numerous; a slightly larger cache avoids re-inflating
        // while scrolling a long note on a slow device.
        blockList.setItemViewCacheSize(12);
        PerformanceMonitor.reportFirstFrame(findViewById(android.R.id.content), "Note editor");
        PerformanceMonitor.profileScrolling(blockList, "Block editor");
        setupPreviewPane();
        applyContentInsets();

        headerView.setListener(new NoteHeaderView.Listener() {
            @Override public void onTitleChanged(String title) { onTitleEdited(title); }

            @Override public void onPickIcon() { showIconPicker(); }

            @Override public void onTagClicked(String tag) { openTag(tag); }

            @Override public void onBacklinksClicked() {
                blockList.smoothScrollToPosition(
                        Math.max(0, blockAdapter.getItemCount()));
            }
        });

        footerView.setListener(new NoteFooterView.Listener() {
            @Override public void onAppendBlock() { appendBlockAtEnd(); }

            @Override public void onOpenNote(Note target) { openNote(target.getId()); }

            @Override public void onCreateNote(String title) { createAndOpen(title); }
        });

        history.setListener((canUndo, canRedo) -> formatBar.setHistoryState(canUndo, canRedo));
    }

    /** Reorders only document blocks; the header and footer stay fixed. */
    private void setupBlockDragging() {
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean isLongPressDragEnabled() { return false; }

            @Override
            public int getMovementFlags(RecyclerView recyclerView,
                                        RecyclerView.ViewHolder holder) {
                if (holder.getBindingAdapter() != blockAdapter || blockAdapter.isReadOnly()) {
                    return makeMovementFlags(0, 0);
                }
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder holder,
                                  RecyclerView.ViewHolder target) {
                if (holder.getBindingAdapter() != blockAdapter
                        || target.getBindingAdapter() != blockAdapter) return false;
                int from = holder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                blockAdapter.moveBlock(from, to);
                return true;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                super.onSelectedChanged(holder, actionState);
                if (holder == null) return;
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    // Make the long-press affordance obvious: the block lifts out
                    // of the list and follows the finger like a draggable card.
                    holder.itemView.animate().cancel();
                    holder.itemView.animate()
                            .scaleX(1.025f).scaleY(1.025f)
                            .alpha(0.92f)
                            .setDuration(140L)
                            .start();
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        holder.itemView.animate().translationZ(Ui.dp(NoteEditorActivity.this, 8))
                                .setDuration(140L).start();
                    }
                }
            }

            @Override
            public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
                super.clearView(recyclerView, holder);
                holder.itemView.animate().cancel();
                holder.itemView.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(180L).start();
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    holder.itemView.animate().translationZ(0f).setDuration(180L).start();
                }
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder holder, int direction) {}
        };
        blockTouchHelper = new ItemTouchHelper(callback);
        blockTouchHelper.attachToRecyclerView(blockList);
    }

    /**
     * The second pane: a read-only preview of this note, or a second note when
     * the user opens one alongside it. The adapter is deliberately read-only so
     * focus never leaves the active editor.
     */
    private void setupPreviewPane() {
        previewAdapter = new BlockAdapter(this, new PreviewHost(), document);
        previewAdapter.setBaseTextSize(prefs.fontSize());
        previewAdapter.setMonospaceBody(prefs.fontFamily() == Prefs.FONT_MONO);
        previewAdapter.setReadOnly(true);
        previewList.setLayoutManager(new LinearLayoutManager(this));
        previewList.setAdapter(previewAdapter);
        previewList.setItemAnimator(null);

        editorSplit.setRatio(prefs.noteSplitRatio());
        editorSplit.setOnRatioChanged(prefs::setNoteSplitRatio);
        setPreviewVisible(prefs.notePreviewOpen());
    }

    private void setPreviewVisible(boolean visible) {
        editorSplit.setSecondVisible(visible);
        prefs.setNotePreviewOpen(visible);
        android.widget.ImageButton button = findViewById(R.id.btnPreview);
        button.setColorFilter(Ui.themeColor(this, visible
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        button.setContentDescription(getString(
                visible ? R.string.preview_hide : R.string.preview_toggle));
        if (visible) refreshPreview();
    }

    /** Redraws the preview; debounced by the caller, never per keystroke. */
    private void refreshPreview() {
        if (previewAdapter == null || !editorSplit.isSecondVisible()) return;
        previewAdapter.setDocument(secondaryDocument == null ? document : secondaryDocument);
        previewAdapter.setReadOnly(true);
    }

    /**
     * A no-op host: the preview never edits, so every callback that would change
     * the document is deliberately inert.
     */
    private final class PreviewHost implements BlockAdapter.Host {
        @Override public void onDocumentEdited() {}

        @Override public boolean isLinkResolved(String target) {
            return repo.index().resolveLink(target) != null;
        }

        @Override public void onLinkClicked(int kind, String target) {
            NoteEditorActivity.this.onLinkClicked(kind, target);
        }

        @Override public void onSlashOpened(BlockEditText field, int position) {}

        @Override public void onSlashQuery(String query) {}

        @Override public void onSlashClosed() {}

        @Override public void onInlineSuggestion(BlockEditText field, int position, int kind,
                                                 String query, int start, int end) {}

        @Override public void onInlineSuggestionClosed() {}

        @Override public void onBlockMenu(View anchor, int position) {}

        @Override public void onBlockDrag(RecyclerView.ViewHolder holder) {}

        @Override public void onPickImage(int position) {}

        @Override public void onCopyText(String text, int confirmationRes) {
            copyToClipboard(text, confirmationRes);
        }

        @Override public InlineMd inline() {
            return secondaryInline == null ? inline : secondaryInline;
        }
    }

    /** Centres the text column on wide screens so lines stay readable. */
    private void applyContentInsets() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxContent = Ui.dp(this, 720);
        int side = Ui.dp(this, 4);
        if (!prefs.wideEditor() && screenWidth > maxContent) {
            side = (screenWidth - maxContent) / 2;
        }
        blockList.setPadding(side, Ui.dp(this, 2), side, 0);
    }

    private void loadNote() {
        Intent intent = getIntent();
        readMode = intent.getBooleanExtra(EXTRA_READ_MODE, false);
        String noteId = intent.getStringExtra(EXTRA_NOTE_ID);

        if (noteId == null) {
            String folder = intent.getStringExtra(EXTRA_CREATE_IN_FOLDER);
            String title = intent.getStringExtra(EXTRA_INITIAL_TITLE);
            if (title == null || title.trim().isEmpty()) title = getString(R.string.untitled);
            note = repo.createNote(title, folder, "");
            getIntent().putExtra(EXTRA_NOTE_ID, note.getId());
            prefs.pushRecent(note.getId());
            prefs.setLastNoteId(note.getId());
            prefs.recordNavigation(note.getId());
            bindNote("");
            headerView.titleInput().requestFocus();
            headerView.titleInput().setSelection(headerView.titleInput().length());
            Ui.showKeyboard(headerView.titleInput());
            return;
        }

        repo.loadNote(noteId, new Io.Result<Note>() {
            @Override public void onReady(Note loaded) {
                note = loaded;
                prefs.pushRecent(loaded.getId());
                prefs.setLastNoteId(loaded.getId());
                if (!navigatingHistory) prefs.recordNavigation(loaded.getId());
                navigatingHistory = false;
                bindNote(loaded.getContent());
            }

            @Override public void onError(Throwable t) {
                toast(R.string.note_not_found);
                finish();
            }
        });
    }

    private void bindNote(String markdown) {
        document = BlockDocument.parse(markdown == null ? "" : markdown);
        inline.setReferenceResolver(document);
        // A pane must never show the same note twice after history navigation.
        if (note != null && note.getId().equals(secondaryNoteId)) clearSecondNote();
        blockAdapter.setDocument(document);
        blockAdapter.ensureTrailingParagraph();
        refreshPreview();
        history.reset(document.toMarkdown());
        setReadMode(readMode);
        refreshChrome();
        restoreCursorState();
        refreshNavigationButtons();
    }

    private void refreshChrome() {
        if (note == null) return;
        String folder = note.folder();
        breadcrumb.setText(folder.isEmpty()
                ? getString(R.string.vault_root) : folder.replace("/", "  ›  "));
        headerView.bind(note, repo.index().backlinkCount(note),
                RelativeTime.format(this, note.getModifiedMillis()));
        footerView.bind(repo.index().backlinksOf(note),
                repo.index().unresolvedLinksOf(note));
    }

    @Override
    protected void onPause() {
        super.onPause();
        Io.cancelMain(renameTask);
        renameTask.run();
        saveCursorState();
        Io.cancelMain(autosave);
        Io.cancelMain(previewRefresh);
        history.breakRun();
        if (dirty) commit(true);
        else if (note != null) repo.flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Io.cancelMain(renameTask);
        renameTask.run();
        saveCursorState();
        commit(true);
        if (note != null) outState.putString(EXTRA_NOTE_ID, note.getId());
        outState.putBoolean(EXTRA_READ_MODE, readMode);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!refreshPropertiesOnResume || note == null) return;
        refreshPropertiesOnResume = false;
        Note indexed = repo.meta(note.getId());
        if (indexed != null) {
            note.setAliases(new ArrayList<>(indexed.getAliases()));
            note.setFrontMatterExtra(new ArrayList<>(indexed.getFrontMatterExtra()));
        }
    }

    @Override
    @android.annotation.SuppressLint("MissingSuperCall")
    public void onBackPressed() {
        if (slashMenu != null && slashMenu.isShowing()) {
            slashMenu.dismiss();
            return;
        }
        finishAfterSave();
    }

    private void finishAfterSave() {
        saveCursorState();
        Io.cancelMain(autosave);
        history.breakRun();
        commit(true);
        Ui.hideKeyboard(blockList);
        finish();
    }

    // ---------------------------------------------------------------- saving

    /**
     * Serialises the document and hands it to the repository.
     *
     * <p>Does nothing when nothing was edited. Round-tripping normalises a few
     * cosmetic details (table column padding, for instance), so committing
     * unconditionally would rewrite every note merely by opening it — bumping its
     * modified time, adding version-history noise and, in a synced folder,
     * manufacturing conflicts.
     */
    private void commit(boolean snapshot) {
        if (note == null || restoring || !dirty) return;
        String markdown = rawMode ? rawMarkdown.getText().toString() : document.toMarkdown();
        String previous = note.getContent();
        if (previous != null && previous.equals(markdown)) {
            dirty = false;
            showStatus(R.string.saved, false);
            return;
        }
        note.setContent(markdown);
        repo.save(note, snapshot);
        if (markdown.contains("📅") || markdown.contains("@due(")
                || markdown.contains("@time(") || markdown.contains("@remind(")) {
            Io.load(() -> com.ccs.shard.core.TaskRepository.scan(repo),
                    new Io.Ok<List<com.ccs.shard.core.TaskItem>>() {
                        @Override public void onReady(List<com.ccs.shard.core.TaskItem> tasks) {
                            com.ccs.shard.core.TaskRepository.scheduleReminders(
                                    NoteEditorActivity.this, tasks);
                        }
                    });
        }
        dirty = false;
        showStatus(R.string.saved, false);
        refreshChrome();
    }

    private void showStatus(int textRes, boolean pending) {
        saveStatus.setText(textRes);
        saveStatus.animate().alpha(1f).setDuration(120).start();
        if (!pending) {
            saveStatus.animate().alpha(0f).setStartDelay(1400).setDuration(400).start();
        } else {
            saveStatus.animate().setStartDelay(0);
        }
    }

    @Override
    public void onDocumentEdited() {
        dirty = true;
        showStatus(R.string.saving, true);
        history.record(document.toMarkdown(), System.currentTimeMillis());
        Io.cancelMain(autosave);
        Io.onMainDelayed(autosave, prefs.autosaveDelayMs());
        Io.cancelMain(previewRefresh);
        Io.onMainDelayed(previewRefresh, PREVIEW_DELAY_MS);
    }

    private void onRawMarkdownEdited() {
        dirty = true;
        showStatus(R.string.saving, true);
        history.record(rawMarkdown.getText().toString(), System.currentTimeMillis());
        Io.cancelMain(autosave);
        Io.onMainDelayed(autosave, prefs.autosaveDelayMs());
    }

    // ---------------------------------------------------------------- host

    @Override
    public boolean isLinkResolved(String target) {
        return repo.index().resolveLink(target) != null;
    }

    @Override
    public void onLinkClicked(int kind, String target) {
        switch (kind) {
            case LinkSpan.WIKI: {
                Note resolved = repo.index().resolveLink(target);
                if (resolved != null) openNote(resolved.getId());
                else createAndOpen(target);
                break;
            }
            case LinkSpan.TAG:
                openTag(target);
                break;
            case LinkSpan.FOOTNOTE:
                int position = document == null ? -1 : document.indexOfFootnote(target);
                if (position >= 0) blockList.smoothScrollToPosition(position + 1);
                break;
            case LinkSpan.URL:
            default:
                openUrl(target);
                break;
        }
    }

    @Override
    public InlineMd inline() { return inline; }

    @Override
    public void onCopyText(String text, int confirmationRes) {
        copyToClipboard(text, confirmationRes);
    }

    @Override
    public void onPickImage(int position) {
        pendingImageBlock = position;
        imagePicker.launch("image/*");
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) return;
        final int position = pendingImageBlock;
        pendingImageBlock = -1;
        Io.onDisk(() -> {
            String reference = null;
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    in.close();
                    String name = "image-" + System.currentTimeMillis() + guessExtension(uri);
                    reference = repo.importAttachment(name, out.toByteArray());
                }
            } catch (Throwable t) {
                android.util.Log.w("ShardEditor", "cannot import image", t);
            }
            final String finalReference = reference;
            Io.onMain(() -> {
                if (finalReference == null) {
                    toast(R.string.image_import_failed);
                    return;
                }
                insertImageBlock(position, finalReference);
            });
        });
    }

    private String guessExtension(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type == null) return ".png";
        if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
        if (type.contains("webp")) return ".webp";
        if (type.contains("gif")) return ".gif";
        return ".png";
    }

    private void insertImageBlock(int position, String reference) {
        int at = position >= 0 && position < document.size()
                ? position : Math.max(0, document.size() - 1);
        Block target = document.get(at);
        if (target != null && target.isEmpty() && target.type.isText) {
            document.blocks().set(at, Block.image(reference, ""));
            document.markDefinitionsDirty();
            blockAdapter.notifyItemChanged(at);
        } else {
            blockAdapter.insertAfter(at, Block.image(reference, ""));
        }
        blockAdapter.ensureTrailingParagraph();
        onDocumentEdited();
    }

    // ---------------------------------------------------------------- slash palette

    @Override
    public void onSlashOpened(BlockEditText field, int position) {
        slashField = field;
        if (slashMenu == null) slashMenu = new SlashMenu(this, this);
        Rect caret = field.caretRectOnScreen();
        slashMenu.show(blockList, caret);
    }

    @Override
    public void onSlashQuery(String query) {
        if (slashMenu == null || !slashMenu.isShowing()) return;
        if (!slashMenu.setQuery(query)) {
            // Nothing matches any more: get out of the way rather than sit empty.
            if (query.length() > 12) slashMenu.dismiss();
        }
        if (slashField != null) slashMenu.reposition(blockList, slashField.caretRectOnScreen());
    }

    @Override
    public void onSlashClosed() {
        if (slashMenu != null) slashMenu.dismiss();
        slashField = null;
    }

    @Override
    public void onInlineSuggestion(BlockEditText field, int position, int kind,
                                   String query, int replaceStart, int replaceEnd) {
        inlineSuggestions.show(field, kind, query, replaceStart, replaceEnd, note);
    }

    @Override
    public void onInlineSuggestionClosed() {
        inlineSuggestions.dismiss();
    }

    @Override
    public void onCommand(SlashCommand command) {
        int position = blockAdapter.caretPosition();
        blockAdapter.consumeSlashText();
        slashField = null;
        applyCommand(command, position);
    }

    @Override
    public void onCancelled() {
        slashField = null;
    }

    private void applyCommand(SlashCommand command, int position) {
        switch (command.id) {
            case SlashCommand.ID_LINK_NOTE:
                showNotePicker();
                return;
            case SlashCommand.ID_TAG:
                showTagPrompt();
                return;
            case SlashCommand.ID_DATE:
                blockAdapter.insertAtCaret(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(new java.util.Date()));
                onDocumentEdited();
                return;
            case SlashCommand.ID_CALLOUT: {
                Block block = document.get(position);
                if (block != null) {
                    block.convertTo(BlockType.CALLOUT);
                    document.markDefinitionsDirty();
                    block.calloutKind = "note";
                    blockAdapter.notifyItemChanged(position);
                    blockAdapter.requestFocusAtEnd(position);
                    onDocumentEdited();
                }
                return;
            }
            default:
                break;
        }
        BlockType type = command.blockType();
        if (type != null) blockAdapter.convert(position, type);
    }

    // ---------------------------------------------------------------- block menu

    @Override
    public void onBlockMenu(View anchor, final int position) {
        blockActions.showBlockMenu(anchor, position);
    }

    @Override
    public void onBlockDrag(RecyclerView.ViewHolder holder) {
        if (blockTouchHelper != null && !blockAdapter.isReadOnly()) {
            blockTouchHelper.startDrag(holder);
        }
    }

    // ---------------------------------------------------------------- format bar

    @Override
    public void onFormatAction(int action) {
        if (rawMode) {
            handleRawFormatAction(action);
            return;
        }
        int position = blockAdapter.caretPosition();
        switch (action) {
            case FormatBar.ACTION_INSERT_BLOCK: {
                View anchor = formatBar.getChildAt(0);
                showInsertMenu(anchor == null ? formatBar : anchor, position);
                break;
            }
            case FormatBar.ACTION_BOLD:
                blockAdapter.wrapSelection("**", "**");
                onDocumentEdited();
                break;
            case FormatBar.ACTION_ITALIC:
                blockAdapter.wrapSelection("*", "*");
                onDocumentEdited();
                break;
            case FormatBar.ACTION_STRIKE:
                blockAdapter.wrapSelection("~~", "~~");
                onDocumentEdited();
                break;
            case FormatBar.ACTION_CODE:
                blockAdapter.wrapSelection("`", "`");
                onDocumentEdited();
                break;
            case FormatBar.ACTION_LINK:
                showLinkPrompt();
                break;
            case FormatBar.ACTION_WIKI_LINK:
                showNotePicker();
                break;
            case FormatBar.ACTION_TODO:
                blockAdapter.convert(position, BlockType.TODO);
                break;
            case FormatBar.ACTION_BULLET:
                blockAdapter.convert(position, BlockType.BULLET);
                break;
            case FormatBar.ACTION_OUTDENT:
                blockAdapter.indentBlock(position, -1);
                break;
            case FormatBar.ACTION_INDENT:
                blockAdapter.indentBlock(position, 1);
                break;
            case FormatBar.ACTION_UNDO:
                restoreSnapshot(history.undo());
                break;
            case FormatBar.ACTION_REDO:
                restoreSnapshot(history.redo());
                break;
            case FormatBar.ACTION_DONE:
                Ui.hideKeyboard(blockList);
                blockList.requestFocus();
                break;
            default:
                break;
        }
    }

    private void showInsertMenu(View anchor, final int position) {
        blockActions.showInsertMenu(anchor, position, this::applyCommand);
    }

    private void restoreSnapshot(String markdown) {
        if (markdown == null) return;
        if (rawMode) {
            int caret = Math.max(0, rawMarkdown.getSelectionStart());
            bindingRawMarkdown = true;
            try {
                rawMarkdown.setText(markdown);
                rawMarkdown.setSelection(Math.min(caret, rawMarkdown.length()));
            } finally {
                bindingRawMarkdown = false;
            }
            dirty = true;
            highlightRawMarkdown();
            Io.cancelMain(autosave);
            Io.onMainDelayed(autosave, prefs.autosaveDelayMs());
            return;
        }
        restoring = true;
        try {
            document = BlockDocument.parse(markdown);
            inline.setReferenceResolver(document);
            blockAdapter.setDocument(document);
            blockAdapter.ensureTrailingParagraph();
            // Undo replaces the document object, so the preview must be re-pointed.
            refreshPreview();
        } finally {
            restoring = false;
        }
        dirty = true;
        Io.cancelMain(autosave);
        Io.onMainDelayed(autosave, prefs.autosaveDelayMs());
    }

    // ---------------------------------------------------------------- prompts

    private void showLinkPrompt() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.insert_link_url);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog()
                .setTitle(R.string.format_link)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    blockAdapter.wrapSelection("[", "](" + url + ")");
                    onDocumentEdited();
                })
                .show();
    }

    private void showTagPrompt() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.tag_name);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog()
                .setTitle(R.string.add_tag)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String tag = input.getText().toString().trim().replace("#", "");
                    if (tag.isEmpty()) return;
                    blockAdapter.insertAtCaret("#" + tag + " ");
                    onDocumentEdited();
                })
                .show();
    }

    private interface NoteSelection {
        void onSelected(Note target);
    }

    /** Note picker for inserting a wiki link. */
    private void showNotePicker() {
        showNotePicker(R.string.link_note_title, true, target -> {
            blockAdapter.insertAtCaret("[[" + target.getTitle() + "]]");
            onDocumentEdited();
        });
    }

    /** Reuses the note picker for links and the side-by-side reader. */
    private void showNotePicker(int titleRes, boolean insertEmptyLink,
                                NoteSelection selection) {
        final List<Note> notes = repo.notes();
        java.util.Collections.sort(notes, (a, b) ->
                Long.compare(b.getModifiedMillis(), a.getModifiedMillis()));
        final List<Note> candidates = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        for (Note candidate : notes) {
            if (note != null && candidate.getId().equals(note.getId())) continue;
            candidates.add(candidate);
            titles.add(candidate.getEmoji().isEmpty()
                    ? candidate.getTitle()
                    : candidate.getEmoji() + "  " + candidate.getTitle());
        }
        if (titles.isEmpty()) {
            if (insertEmptyLink) {
                blockAdapter.insertAtCaret("[[]]");
                onDocumentEdited();
            } else {
                toast(R.string.no_notes_found);
            }
            return;
        }
        dialog()
                .setTitle(titleRes)
                .setItems(titles.toArray(new CharSequence[0]),
                        (d, which) -> selection.onSelected(candidates.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showNoteAlongside() {
        showNotePicker(R.string.open_note_alongside, false,
                target -> openNoteAlongside(target.getId()));
    }

    private void openNoteAlongside(String noteId) {
        if (noteId == null || noteId.equals(note == null ? null : note.getId())) return;
        repo.loadNote(noteId, new Io.Result<Note>() {
            @Override public void onReady(Note loaded) {
                secondaryNoteId = loaded.getId();
                secondaryDocument = BlockDocument.parse(loaded.getContent() == null
                        ? "" : loaded.getContent());
                secondaryInline = new InlineMd(buildPalette());
                secondaryInline.setResolver(target -> repo.index().resolveLink(target) != null);
                secondaryInline.setReferenceResolver(secondaryDocument);
                secondaryPaneTitle.setText(loaded.getEmoji().isEmpty()
                        ? loaded.getTitle() : loaded.getEmoji() + "  " + loaded.getTitle());
                secondaryPaneBar.setVisibility(View.VISIBLE);
                setPreviewVisible(true);
                previewList.post(() -> previewList.scrollToPosition(0));
            }

            @Override public void onError(Throwable t) { toast(R.string.note_not_found); }
        });
    }

    /** Returns the split view to a live preview of the active note. */
    private void clearSecondNote() {
        secondaryNoteId = null;
        secondaryDocument = null;
        secondaryInline = null;
        if (secondaryPaneBar != null) secondaryPaneBar.setVisibility(View.GONE);
        refreshPreview();
    }

    private void showIconPicker() {
        noteActions.showIconPicker(headerView);
    }

    // ---------------------------------------------------------------- note menu

    private void showNoteMenu(View anchor) {
        noteActions.showNoteMenu(anchor);
    }

    private void showFindReplace() {
        final LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        fields.setPadding(pad, 0, pad, 0);
        final EditText find = new EditText(this);
        find.setSingleLine(true);
        find.setHint(R.string.find_text);
        final EditText replacement = new EditText(this);
        replacement.setSingleLine(true);
        replacement.setHint(R.string.replace_with);
        fields.addView(find);
        fields.addView(replacement);
        dialog().setTitle(R.string.find_replace)
                .setView(fields)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.replace_all, (d, which) -> {
                    String needle = find.getText().toString();
                    if (needle.isEmpty()) return;
                    String replacementText = replacement.getText().toString();
                    String source = rawMode ? rawMarkdown.getText().toString()
                            : document.toMarkdown();
                    int count = countOccurrences(source, needle);
                    if (count == 0) {
                        toast(R.string.find_no_matches);
                        return;
                    }
                    String updated = source.replace(needle, replacementText);
                    if (rawMode) {
                        bindingRawMarkdown = true;
                        try { rawMarkdown.setText(updated); }
                        finally { bindingRawMarkdown = false; }
                        onRawMarkdownEdited();
                        highlightRawMarkdown();
                    } else {
                        document = BlockDocument.parse(updated);
                        inline.setReferenceResolver(document);
                        blockAdapter.setDocument(document);
                        blockAdapter.ensureTrailingParagraph();
                        refreshPreview();
                        onDocumentEdited();
                    }
                    toast(getString(R.string.replaced_count, count));
                }).show();
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private void showExportMenu() {
        exportFlow.offerNoteFormats(note, findViewById(R.id.btnMore));
    }

    // ---------------------------------------------------------------- misc

    private void onTitleEdited(String title) {
        if (note == null) return;
        dirty = true;
        Io.cancelMain(renameTask);
        pendingTitle = title;
        Io.onMainDelayed(renameTask, 900);
    }

    private String pendingTitle;

    private final Runnable renameTask = new Runnable() {
        @Override public void run() {
            if (note == null || pendingTitle == null) return;
            String title = pendingTitle.trim();
            pendingTitle = null;
            if (title.isEmpty() || title.equals(note.getTitle())) return;
            commit(false);
            String newId = repo.rename(note, title, true);
            if (newId == null) {
                toast(R.string.rename_conflict);
                return;
            }
            note.setId(newId);
            getIntent().putExtra(EXTRA_NOTE_ID, newId);
            note.setTitle(Md.safeFileName(title));
            // Queue the current body after the rename, including edits saved just before it.
            repo.save(note, false);
            refreshChrome();
        }
    };

    private void setReadMode(boolean value) {
        if (rawMode) return;
        readMode = value;
        applyEditingAvailability();
        readModeButton.setSelected(value);
        readModeButton.setColorFilter(Ui.themeColor(this,
                value ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOnSurfaceVariant));
        readModeButton.setImageResource(value
                ? R.drawable.ic_block_text : R.drawable.ic_open_in_new);
        readModeButton.setContentDescription(getString(value
                ? R.string.edit_mode : R.string.read_mode));
        if (value || editingLocked) {
            Ui.hideKeyboard(blockList);
            blockList.clearFocus();
            showFormatBar(false);
        }
    }

    private void setEditingLocked(boolean value) {
        editingLocked = value;
        applyEditingAvailability();
        lockEditingButton.setImageResource(value
                ? R.drawable.ic_lock : R.drawable.ic_lock_open);
        lockEditingButton.setColorFilter(Ui.themeColor(this,
                value ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOnSurfaceVariant));
        lockEditingButton.setContentDescription(getString(value
                ? R.string.unlock_editing : R.string.lock_editing));
        if (value) {
            Ui.hideKeyboard(rawMode ? rawMarkdown : blockList);
            showFormatBar(false);
        }
    }

    private void applyEditingAvailability() {
        boolean unavailable = readMode || editingLocked;
        blockAdapter.setReadOnly(unavailable);
        headerView.titleInput().setFocusable(!unavailable);
        headerView.titleInput().setFocusableInTouchMode(!unavailable);
        if (rawMarkdown != null) {
            rawMarkdown.setEnabled(!editingLocked);
            rawMarkdown.setCursorVisible(!editingLocked);
        }
    }

    /** Switches between the block editor and an exact plain-text Markdown view. */
    private void setRawMarkdownMode(boolean value) {
        if (rawMode == value || note == null) return;
        if (value) {
            Prefs.CursorState saved = prefs.cursor(note.getId());
            saveCursorState();
            commit(true);
            bindingRawMarkdown = true;
            try { rawMarkdown.setText(note.getContent() == null ? "" : note.getContent()); }
            finally { bindingRawMarkdown = false; }
            rawMode = true;
            rawMarkdown.setEnabled(!editingLocked);
            rawMarkdown.setCursorVisible(!editingLocked);
            editorSplit.setVisibility(View.GONE);
            rawMarkdown.setVisibility(View.VISIBLE);
            rawMarkdown.requestFocus();
            int caret = saved != null && saved.raw ? saved.offset : rawMarkdown.length();
            rawMarkdown.setSelection(Math.min(caret, rawMarkdown.length()));
            prefs.setCursor(note.getId(), true, 0, rawMarkdown.getSelectionStart());
            history.reset(rawMarkdown.getText().toString());
            highlightRawMarkdown();
        } else {
            saveCursorState();
            commit(true);
            rawMode = false;
            prefs.setCursor(note.getId(), false, 0, 0);
            document = BlockDocument.parse(rawMarkdown.getText().toString());
            inline.setReferenceResolver(document);
            blockAdapter.setDocument(document);
            blockAdapter.ensureTrailingParagraph();
            editorSplit.setVisibility(View.VISIBLE);
            rawMarkdown.setVisibility(View.GONE);
            refreshPreview();
            history.reset(document.toMarkdown());
            refreshChrome();
        }
    }

    /** Applies Markdown colours while keeping every source character editable. */
    private void highlightRawMarkdown() {
        if (!rawMode || rawMarkdown == null || rawHighlighter == null) return;
        String source = rawMarkdown.getText().toString();
        int start = Math.max(0, rawMarkdown.getSelectionStart());
        int end = Math.max(0, rawMarkdown.getSelectionEnd());
        CharSequence highlighted = source.length() > RAW_HIGHLIGHT_LIMIT
                ? source : rawHighlighter.highlight(source);
        bindingRawMarkdown = true;
        try {
            rawMarkdown.setText(highlighted, EditText.BufferType.SPANNABLE);
            rawMarkdown.setSelection(Math.min(start, rawMarkdown.length()),
                    Math.min(end, rawMarkdown.length()));
        } finally {
            bindingRawMarkdown = false;
        }
    }

    private void handleRawFormatAction(int action) {
        switch (action) {
            case FormatBar.ACTION_BOLD: wrapRaw("**", "**"); break;
            case FormatBar.ACTION_ITALIC: wrapRaw("*", "*"); break;
            case FormatBar.ACTION_STRIKE: wrapRaw("~~", "~~"); break;
            case FormatBar.ACTION_CODE: wrapRaw("`", "`"); break;
            case FormatBar.ACTION_LINK: wrapRaw("[", "](https://)"); break;
            case FormatBar.ACTION_WIKI_LINK: wrapRaw("[[", "]]" ); break;
            case FormatBar.ACTION_UNDO: restoreSnapshot(history.undo()); break;
            case FormatBar.ACTION_REDO: restoreSnapshot(history.redo()); break;
            case FormatBar.ACTION_DONE:
                Ui.hideKeyboard(rawMarkdown);
                rawMarkdown.clearFocus();
                break;
            default:
                break;
        }
    }

    private void insertRaw(String text) {
        int rawStart = rawMarkdown.getSelectionStart();
        int rawEnd = rawMarkdown.getSelectionEnd();
        int start = Math.max(0, Math.min(rawStart, rawEnd));
        int end = Math.max(start, Math.max(rawStart, rawEnd));
        rawMarkdown.getText().replace(start, end, text);
        rawMarkdown.setSelection(start + text.length());
    }

    private void wrapRaw(String prefix, String suffix) {
        int rawStart = rawMarkdown.getSelectionStart();
        int rawEnd = rawMarkdown.getSelectionEnd();
        int start = Math.max(0, Math.min(rawStart, rawEnd));
        int end = Math.max(start, Math.max(rawStart, rawEnd));
        android.text.Editable editable = rawMarkdown.getText();
        editable.insert(end, suffix);
        editable.insert(start, prefix);
        rawMarkdown.setSelection(start + prefix.length(), end + prefix.length());
    }

    private void saveCursorState() {
        if (note == null) return;
        if (rawMode) {
            prefs.setCursor(note.getId(), true, 0,
                    Math.max(0, rawMarkdown.getSelectionStart()));
        } else {
            prefs.setCursor(note.getId(), false,
                    Math.max(0, blockAdapter.focusedPosition()),
                    Math.max(0, blockAdapter.caretOffset()));
        }
    }

    private void restoreCursorState() {
        if (note == null || readMode) return;
        Prefs.CursorState saved = prefs.cursor(note.getId());
        if (saved == null) return;
        if (saved.raw) {
            rawMarkdown.post(() -> {
                if (!isFinishing() && note != null) setRawMarkdownMode(true);
            });
            return;
        }
        final int block = Math.min(saved.block, Math.max(0, document.size() - 1));
        blockList.post(() -> {
            blockAdapter.requestFocus(block, saved.offset);
            blockList.scrollToPosition(Math.max(0, block + 1));
        });
    }

    private void navigateHistory(int delta) {
        navigateToHistoryTarget(noteNavigator.moveBy(delta));
    }

    private void navigateToHistoryIndex(int index) {
        navigateToHistoryTarget(noteNavigator.selectHistory(index));
    }

    /** Opens a history entry in this editor rather than creating another activity. */
    private void navigateToHistoryTarget(String target) {
        if (target == null || target.equals(note == null ? null : note.getId())) return;
        saveCursorState();
        commit(true);
        navigatingHistory = true;
        if (rawMode) {
            rawMode = false;
            editorSplit.setVisibility(View.VISIBLE);
            rawMarkdown.setVisibility(View.GONE);
        }
        getIntent().putExtra(EXTRA_NOTE_ID, target);
        loadNote();
    }

    /** Displays the chronological trail behind the previous/next buttons. */
    private void showOpenedNotes() {
        showOpenedNotes(historyBackButton);
    }

    private void showOpenedNotes(View anchor) {
        final List<NoteNavigator.HistoryEntry> entries = noteNavigator.openedNotes();
        if (entries.isEmpty()) {
            toast(R.string.no_notes_found);
            return;
        }
        AnchoredMenu menu = AnchoredMenu.vertical(this)
                .title(getString(R.string.opened_notes));
        for (NoteNavigator.HistoryEntry entry : entries) {
            String label = entry.emoji().isEmpty()
                    ? entry.title() : entry.emoji() + "  " + entry.title();
            menu.add(new AnchoredMenu.Item(entry.index(), R.drawable.ic_recent, label)
                    .hint(entry.folder().isEmpty()
                            ? getString(R.string.vault_root) : entry.folder())
                    .checked(entry.isCurrent()));
        }
        menu.onItem(this::navigateToHistoryIndex).showAt(anchor);
    }

    /** Turns the visible folder path into navigable ancestor breadcrumbs. */
    private void showBreadcrumbMenu(View anchor) {
        String currentFolder = note == null ? "" : note.folder();
        final List<NoteNavigator.Breadcrumb> breadcrumbs =
                noteNavigator.breadcrumbs(currentFolder);
        AnchoredMenu menu = AnchoredMenu.vertical(this)
                .title(getString(R.string.vault_root));
        for (int i = 0; i < breadcrumbs.size(); i++) {
            NoteNavigator.Breadcrumb breadcrumb = breadcrumbs.get(i);
            String folder = breadcrumb.path();
            menu.add(new AnchoredMenu.Item(i, R.drawable.ic_folder,
                    folder.isEmpty() ? getString(R.string.vault_root) : folder)
                    .checked(breadcrumb.isCurrent()));
        }
        menu.onItem(id -> openFolder(breadcrumbs.get(id).path())).showAt(anchor);
    }

    /** Shows document headings and scrolls directly to the selected block. */
    private void showHeadingNavigator() {
        final List<NoteNavigator.Heading> headings = noteNavigator.headings(document);
        if (headings.isEmpty()) {
            toast(R.string.no_headings);
            return;
        }
        AnchoredMenu menu = AnchoredMenu.vertical(this)
                .title(getString(R.string.go_to_heading));
        for (NoteNavigator.Heading heading : headings) {
            BlockType type = heading.type();
            int icon = type == BlockType.HEADING_1 ? R.drawable.ic_block_h1
                    : type == BlockType.HEADING_2 ? R.drawable.ic_block_h2
                    : R.drawable.ic_block_h3;
            menu.add(heading.blockIndex(), icon, heading.title());
        }
        menu.onItem(position -> {
            if (rawMode) setRawMarkdownMode(false);
            blockList.post(() -> blockList.smoothScrollToPosition(position + 1));
        }).showAt(blockList);
    }

    private void refreshNavigationButtons() {
        if (historyBackButton == null) return;
        historyBackButton.setEnabled(prefs.canNavigate(-1));
        historyBackButton.setAlpha(historyBackButton.isEnabled() ? 1f : 0.3f);
        historyForwardButton.setEnabled(prefs.canNavigate(1));
        historyForwardButton.setAlpha(historyForwardButton.isEnabled() ? 1f : 0.3f);
    }

    private void appendBlockAtEnd() {
        if (readMode) return;
        blockAdapter.ensureTrailingParagraph();
        int last = document.size() - 1;
        blockAdapter.requestFocusAtEnd(last);
        blockList.scrollToPosition(blockList.getAdapter().getItemCount() - 1);
    }

    private void openNote(String noteId) {
        commit(true);
        Intent intent = new Intent(this, NoteEditorActivity.class);
        intent.putExtra(EXTRA_NOTE_ID, noteId);
        startActivity(intent);
    }

    private void createAndOpen(String title) {
        commit(true);
        if (repo.vault().isSharedStorage() && !repo.vault().hasFullFileAccess()) {
            toast(R.string.storage_locked_title);
            return;
        }
        Note created = repo.createNote(title, note == null ? "" : note.folder(), "");
        openNote(created.getId());
    }

    private void openTag(String tag) {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.putExtra(HomeActivity.EXTRA_FILTER_TAG, tag);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void openFolder() {
        openFolder(note == null ? "" : note.folder());
    }

    private void openFolder(String folder) {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.putExtra(HomeActivity.EXTRA_FOLDER, folder);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast(R.string.cannot_open_link);
        }
    }

    /**
     * Shows the format bar only while the keyboard is up. Watching the visible
     * frame is the one approach that works on every vendor skin, including
     * Samsung's, where the IME inset APIs under-report on older releases.
     */
    private void observeKeyboard() {
        final View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visible = new Rect();
            root.getWindowVisibleDisplayFrame(visible);
            int screenHeight = root.getRootView().getHeight();
            boolean keyboardUp = screenHeight - visible.height() > screenHeight * 0.15;
            showFormatBar(keyboardUp && !readMode);
        });
    }

    private void showFormatBar(boolean visible) {
        int want = visible ? View.VISIBLE : View.GONE;
        if (formatBarHolder.getVisibility() == want) return;
        formatBarHolder.setVisibility(want);
        formatBarDivider.setVisibility(want);
    }

    /** Bridges {@link EditorIntents} to this screen's note loading. */
    private final class EditorIntentsHost implements EditorIntents.Host {
        @Override public void loadNoteId(String noteId) {
            getIntent().putExtra(EXTRA_NOTE_ID, noteId);
            loadNote();
        }

        @Override public void adoptNote(Note loaded, String markdown) {
            note = loaded;
            getIntent().putExtra(EXTRA_NOTE_ID, loaded.getId());
            bindNote(markdown);
        }

        @Override public void showSavingStatus() { showStatus(R.string.saving, true); }

        @Override public void close() { finish(); }
    }

    /** Bridges {@link NoteActions} to this screen's note and navigation. */
    private final class NoteActionsHost implements NoteActions.Host {
        @Override public Note note() { return note; }

        @Override public int blockCount() { return document.size(); }

        @Override public void commit() { NoteEditorActivity.this.commit(true); }

        @Override public void refreshChrome() { NoteEditorActivity.this.refreshChrome(); }

        @Override public void openNote(String noteId) {
            NoteEditorActivity.this.openNote(noteId);
        }

        @Override public void cancelPendingSave() {
            dirty = false;
            Io.cancelMain(autosave);
        }

        @Override public void onNoteDeleted() { finish(); }

        @Override public void showExportMenu() {
            NoteEditorActivity.this.showExportMenu();
        }

        @Override public void onPropertiesOpened() { refreshPropertiesOnResume = true; }

        @Override public void toggleRawMarkdown() { setRawMarkdownMode(!rawMode); }

        @Override public void showFindReplace() { NoteEditorActivity.this.showFindReplace(); }

        @Override public void showOpenedNotes() { NoteEditorActivity.this.showOpenedNotes(); }

        @Override public void showHeadingNavigator() {
            NoteEditorActivity.this.showHeadingNavigator();
        }

        @Override public void showNoteAlongside() {
            NoteEditorActivity.this.showNoteAlongside();
        }
    }

    /** Bridges {@link BlockActions} to this screen's document and adapter. */
    private final class BlockActionsHost implements BlockActions.Host {
        @Override public BlockDocument document() { return document; }

        @Override public BlockAdapter adapter() { return blockAdapter; }

        @Override public void onDocumentEdited() { NoteEditorActivity.this.onDocumentEdited(); }

        @Override public void copyBlockMarkdown(String markdown) {
            copyToClipboard(markdown, R.string.copied_note);
        }
    }

    /** Convenience entry point used from every screen that opens a note. */
    public static void open(android.content.Context context, String noteId) {
        if (NoteFile.isTexFile(noteId)) {
            TexEditorActivity.open(context, noteId);
            return;
        }
        Intent intent = new Intent(context, NoteEditorActivity.class);
        intent.putExtra(EXTRA_NOTE_ID, noteId);
        context.startActivity(intent);
    }

    public static void create(android.content.Context context, String folder, String title) {
        Intent intent = new Intent(context, NoteEditorActivity.class);
        intent.putExtra(EXTRA_CREATE_IN_FOLDER, folder);
        if (title != null) intent.putExtra(EXTRA_INITIAL_TITLE, title);
        context.startActivity(intent);
    }
}
