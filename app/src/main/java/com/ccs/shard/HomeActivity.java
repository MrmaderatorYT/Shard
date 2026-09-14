package com.ccs.shard;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.DailyNotes;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.NoteIndex;
import com.ccs.shard.core.NoteProperties;
import com.ccs.shard.core.NoteSelection;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.io.ExportFlow;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.CommandPalette;
import com.ccs.shard.ui.Ui;
import com.ccs.shard.ui.VaultItem;
import com.ccs.shard.ui.VaultListAdapter;
import com.ccs.shard.ui.VaultDrawer;
import com.ccs.shard.io.ImportFlow;
import com.ccs.shard.util.NoteSorter;
import com.ccs.shard.util.PerformanceMonitor;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The vault browser: search, folders and notes.
 *
 * <p>Structured around one idea — the list is always "what you asked for", and
 * everything that changes <em>what</em> you are looking at lives in the drawer.
 * That keeps the main surface to a single search row instead of the four stacked
 * bars the previous version needed, and it gives folders, tags, favourites and
 * recents one consistent place to be found.
 */
public final class HomeActivity extends BaseActivity
        implements VaultListAdapter.Listener, VaultRepository.Listener {

    public static final String EXTRA_FOLDER = "folder";
    public static final String EXTRA_FILTER_TAG = "filter_tag";
    public static final String EXTRA_VIEW = "view";

    private static final int VIEW_FOLDER = 0;
    private static final int VIEW_ALL = 1;
    private static final int VIEW_FAVOURITES = 2;
    private static final int VIEW_RECENT = 3;
    private static final int VIEW_TAG = 4;

    private DrawerLayout drawer;
    private LinearLayout drawerContent;
    private RecyclerView list;
    private VaultListAdapter adapter;
    private ExportFlow exportFlow;
    private VaultDrawer vaultDrawer;
    private ImportFlow importFlow;
    private EditText searchInput;
    private ImageButton clearSearch;
    private View breadcrumbBar;
    private LinearLayout breadcrumbRow;
    private TextView contextLabel;
    private SwipeRefreshLayout refresh;
    private ExtendedFloatingActionButton fab;
    private View homeToolbar;
    private View selectionBar;
    private TextView selectionCount;
    private View storageBanner;
    private boolean bannerDismissed;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptyBody;
    private MaterialButton emptyAction;

    private int view = VIEW_FOLDER;
    private String folder = "";
    private String tagFilter;
    private String query = "";
    private int blockTypeFilter;
    private int dateFilterDays;

    private boolean relocatingVault;
    private boolean relocationFailed;
    private boolean restoreLastNoteOnOpen;
    private final NoteSelection noteSelection = new NoteSelection();

    private final Runnable searchTask = new Runnable() {
        @Override public void run() { runSearch(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreLastNoteOnOpen = savedInstanceState == null
                && !getIntent().hasExtra(EXTRA_FOLDER)
                && !getIntent().hasExtra(EXTRA_FILTER_TAG)
                && !getIntent().hasExtra(EXTRA_VIEW);
        setContentView(R.layout.activity_home);

        drawer = findViewById(R.id.drawerLayout);
        drawerContent = findViewById(R.id.drawerContent);
        list = findViewById(R.id.vaultList);
        searchInput = findViewById(R.id.searchInput);
        clearSearch = findViewById(R.id.btnClearSearch);
        breadcrumbBar = findViewById(R.id.breadcrumbBar);
        breadcrumbRow = findViewById(R.id.breadcrumbRow);
        contextLabel = findViewById(R.id.contextLabel);
        refresh = findViewById(R.id.refresh);
        fab = findViewById(R.id.fabNewNote);
        storageBanner = findViewById(R.id.storageBanner);
        emptyState = findViewById(R.id.emptyState);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptyBody = findViewById(R.id.emptyBody);
        emptyAction = findViewById(R.id.emptyAction);
        homeToolbar = findViewById(R.id.homeToolbar);
        selectionBar = findViewById(R.id.selectionBar);
        selectionCount = findViewById(R.id.selectionCount);

        exportFlow = new ExportFlow(this, repo);
        importFlow = new ImportFlow(this, repo, new ImportHost());
        vaultDrawer = new VaultDrawer(this, repo, drawerContent, new DrawerHost());
        adapter = new VaultListAdapter(this, this);
        folder = prefs.lastFolder();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setItemAnimator(null);
        list.setItemViewCacheSize(10);
        PerformanceMonitor.reportFirstFrame(findViewById(android.R.id.content), "Home");
        PerformanceMonitor.profileScrolling(list, "Vault list");

        findViewById(R.id.btnMenu).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.btnCommand).setOnClickListener(v -> showCommandPalette());
        findViewById(R.id.btnSort).setOnClickListener(this::showSortMenu);
        findViewById(R.id.btnSelectionClose).setOnClickListener(v -> exitSelection());
        findViewById(R.id.btnSelectionAll).setOnClickListener(v -> toggleSelectAllVisible());
        findViewById(R.id.btnSelectionActions).setOnClickListener(this::showBulkActions);
        fab.setOnClickListener(this::showNewNoteMenu);
        emptyAction.setOnClickListener(v -> showImportMenu(v));
        clearSearch.setOnClickListener(v -> {
            searchInput.setText("");
            Ui.hideKeyboard(searchInput);
        });

        findViewById(R.id.btnDismissBanner).setOnClickListener(v -> {
            bannerDismissed = true;
            storageBanner.setVisibility(View.GONE);
        });

        refresh.setOnRefreshListener(() -> {
            repo.refresh();
            list.postDelayed(() -> refresh.setRefreshing(false), 600);
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                Io.cancelMain(searchTask);
                // Short debounce: full-text search reads files, so it should not
                // start on every keystroke.
                Io.onMainDelayed(searchTask, query.isEmpty() ? 0 : 220);
            }
        });

        attachSwipeActions();
        applyIntent(getIntent());
        buildDrawer();
        repo.addListener(this);
        repo.open(() -> {
            rebuild();
            restoreLastOpenedNote();
        });
    }

    private void restoreLastOpenedNote() {
        if (!restoreLastNoteOnOpen) return;
        restoreLastNoteOnOpen = false;
        String id = prefs.lastNoteId();
        if (id != null && repo.meta(id) != null) NoteEditorActivity.open(this, id);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
        rebuild();
    }

    private void applyIntent(Intent intent) {
        String tag = intent.getStringExtra(EXTRA_FILTER_TAG);
        String targetFolder = intent.getStringExtra(EXTRA_FOLDER);
        int requestedView = intent.getIntExtra(EXTRA_VIEW, -1);
        if (tag != null) {
            view = VIEW_TAG;
            tagFilter = tag;
        } else if (requestedView >= 0) {
            view = requestedView;
        } else if (targetFolder != null) {
            view = VIEW_FOLDER;
            folder = targetFolder;
            prefs.setLastFolder(folder);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    protected void onDestroy() {
        repo.removeListener(this);
        Io.cancelMain(searchTask);
        super.onDestroy();
    }

    @Override
    public void onVaultChanged() {
        rebuild();
        buildDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        if (noteSelection.isActive()) {
            exitSelection();
            return;
        }
        if (!query.isEmpty()) {
            searchInput.setText("");
            return;
        }
        if (view != VIEW_FOLDER) {
            view = VIEW_FOLDER;
            tagFilter = null;
            rebuild();
            return;
        }
        if (!folder.isEmpty()) {
            int slash = folder.lastIndexOf('/');
            folder = slash < 0 ? "" : folder.substring(0, slash);
            prefs.setLastFolder(folder);
            rebuild();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- content

    private String currentFolderForNew() {
        return view == VIEW_FOLDER ? folder : "";
    }

    private boolean ensureDocumentsVault() {
        return true;
    }

    private void createMarkdownNote() {
        if (ensureDocumentsVault()) {
            NoteEditorActivity.create(this, currentFolderForNew(), null);
        }
    }

    private void createTexNote() {
        if (ensureDocumentsVault()) TexEditorActivity.create(this, currentFolderForNew());
    }

    private void showNewNoteMenu(View anchor) {
        if (!ensureDocumentsVault()) return;
        AnchoredMenu.vertical(this)
                .title(getString(R.string.new_note))
                .add(new AnchoredMenu.Item(1, R.drawable.ic_notes,
                        getString(R.string.new_markdown_note))
                        .hint(getString(R.string.new_markdown_note_hint)))
                .add(new AnchoredMenu.Item(2, R.drawable.ic_block_code,
                        getString(R.string.command_new_tex))
                        .hint(getString(R.string.new_tex_note_hint)))
                .onItem(id -> {
                    if (id == 1) createMarkdownNote();
                    else if (id == 2) createTexNote();
                })
                .showAt(anchor);
    }

    /** Rebuilds the list for the current view, folder, tag and query. */
    private void rebuild() {
        pruneSelection();
        adapter.setHighlightQuery("");
        if (!query.isEmpty()) {
            runSearch();
            return;
        }
        NoteIndex index = repo.index();
        List<VaultItem> items = new ArrayList<>();

        switch (view) {
            case VIEW_ALL:
                addNotes(items, filterVisible(index.all()), R.string.section_notes);
                break;
            case VIEW_FAVOURITES: {
                List<Note> favourites = new ArrayList<>();
                for (Note note : index.all()) {
                    if (note.isBookmarked()) favourites.add(note);
                }
                addNotes(items, favourites, R.string.nav_favourites);
                break;
            }
            case VIEW_RECENT: {
                List<Note> recents = new ArrayList<>();
                for (String id : prefs.recents()) {
                    Note note = index.get(id);
                    if (note != null) recents.add(note);
                }
                // Already in recency order; keep it rather than re-sorting.
                if (!recents.isEmpty()) items.add(VaultItem.header(getString(R.string.nav_recent)));
                for (Note note : recents) items.add(VaultItem.note(note));
                break;
            }
            case VIEW_TAG:
                addNotes(items, filterVisible(index.withTag(tagFilter)),
                        R.string.section_notes);
                break;
            case VIEW_FOLDER:
            default: {
                List<NoteIndex.FolderEntry> folders = index.foldersIn(folder);
                List<Note> notes = filterVisible(index.inFolder(folder));
                if (prefs.groupFolders() && !folders.isEmpty()) {
                    items.add(VaultItem.header(getString(R.string.section_folders)));
                    for (NoteIndex.FolderEntry entry : folders) items.add(VaultItem.folder(entry));
                }
                addNotes(items, notes, R.string.section_notes);
                if (!prefs.groupFolders()) {
                    for (NoteIndex.FolderEntry entry : folders) items.add(VaultItem.folder(entry));
                }
                break;
            }
        }

        adapter.submit(items);
        updateChrome(items.isEmpty());
    }

    private void pruneSelection() {
        if (!noteSelection.isActive()) return;
        List<String> available = new ArrayList<>();
        for (String id : noteSelection.ids()) {
            if (repo.meta(id) != null) available.add(id);
        }
        noteSelection.retainOnly(available);
        if (noteSelection.isEmpty()) exitSelection();
        else adapter.setSelectedIds(noteSelection.ids());
    }

    private List<Note> filterVisible(List<Note> notes) {
        List<Note> out = new ArrayList<>(notes.size());
        long cutoff = dateFilterDays <= 0 ? 0L
                : System.currentTimeMillis() - dateFilterDays * 24L * 60L * 60L * 1000L;
        for (Note note : notes) {
            if (!prefs.showArchived() && note.isArchived()) continue;
            if (blockTypeFilter != 0 && !note.hasBlockType(blockTypeFilter)) continue;
            if (cutoff > 0 && note.getModifiedMillis() < cutoff) continue;
            out.add(note);
        }
        return out;
    }

    /** Splits pinned notes into their own section, then appends the rest. */
    private void addNotes(List<VaultItem> items, List<Note> notes, int sectionRes) {
        NoteSorter.sort(notes, prefs.sortMode(), prefs.sortDescending());
        List<Note> pinned = new ArrayList<>();
        List<Note> rest = new ArrayList<>();
        for (Note note : notes) {
            if (note.isPinned()) pinned.add(note);
            else rest.add(note);
        }
        if (!pinned.isEmpty()) {
            items.add(VaultItem.header(getString(R.string.section_pinned)));
            for (Note note : pinned) items.add(VaultItem.note(note));
        }
        if (!rest.isEmpty()) {
            items.add(VaultItem.header(getString(sectionRes)));
            for (Note note : rest) items.add(VaultItem.note(note));
        }
    }

    private void runSearch() {
        if (query.isEmpty()) {
            rebuild();
            return;
        }
        final String searched = query;
        repo.search(query, 80, new Io.Ok<List<VaultRepository.Hit>>() {
            @Override public void onReady(List<VaultRepository.Hit> hits) {
                if (!searched.equals(query)) return;
                List<VaultItem> items = new ArrayList<>();
                List<VaultItem> results = new ArrayList<>();
                for (VaultRepository.Hit hit : hits) {
                    if (filterVisible(java.util.Collections.singletonList(hit.note)).isEmpty()) continue;
                    results.add(VaultItem.note(hit.note, hit.snippet));
                }
                if (!results.isEmpty()) items.add(VaultItem.header(
                        getString(R.string.section_results)));
                items.addAll(results);
                adapter.setHighlightQuery(searched);
                adapter.submit(items);
                updateChrome(items.isEmpty());
            }
        });
    }

    private void updateChrome(boolean empty) {
        updateBreadcrumb();
        updateContextLabel();
        updateStorageBanner();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;

        emptyAction.setVisibility(View.GONE);
        if (!query.isEmpty()) {
            emptyTitle.setText(R.string.empty_search_title);
            emptyBody.setText(getString(R.string.empty_search_body, query));
            return;
        }
        switch (view) {
            case VIEW_FAVOURITES:
                emptyTitle.setText(R.string.empty_favourites_title);
                emptyBody.setText(R.string.empty_favourites_body);
                break;
            case VIEW_RECENT:
                emptyTitle.setText(R.string.empty_recent_title);
                emptyBody.setText(R.string.empty_recent_body);
                break;
            case VIEW_TAG:
                emptyTitle.setText(R.string.empty_folder_title);
                emptyBody.setText(R.string.empty_folder_body);
                break;
            default:
                if (repo.index().size() == 0) {
                    emptyTitle.setText(R.string.empty_vault_title);
                    emptyBody.setText(R.string.empty_vault_body);
                    emptyAction.setVisibility(View.VISIBLE);
                } else {
                    emptyTitle.setText(R.string.empty_folder_title);
                    emptyBody.setText(R.string.empty_folder_body);
                }
                break;
        }
    }

    /** Keeps the required shared-vault access visible until it is granted. */
    private void updateStorageBanner() {
        boolean needsSharedAccess = false;
        boolean show = !bannerDismissed && repo.hasInaccessibleFiles();
        storageBanner.setVisibility(show ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnDismissBanner).setVisibility(
                needsSharedAccess ? View.GONE : View.VISIBLE);
    }


    private void updateContextLabel() {
        List<String> filters = new ArrayList<>();
        if (view == VIEW_TAG && tagFilter != null) filters.add("#" + tagFilter);
        if (blockTypeFilter != 0) filters.add(blockTypeName(blockTypeFilter));
        if (dateFilterDays > 0) filters.add(getResources().getQuantityString(
                R.plurals.filter_last_days, dateFilterDays, dateFilterDays));
        if (!filters.isEmpty()) {
            contextLabel.setVisibility(View.VISIBLE);
            contextLabel.setText(android.text.TextUtils.join("  ·  ", filters));
        } else {
            contextLabel.setVisibility(View.GONE);
        }
    }

    private void updateBreadcrumb() {
        boolean show = view == VIEW_FOLDER && !folder.isEmpty();
        breadcrumbBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        breadcrumbRow.removeAllViews();
        breadcrumbRow.addView(crumb(getString(R.string.vault_root), ""));
        String[] parts = folder.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (path.length() > 0) path.append('/');
            path.append(part);
            breadcrumbRow.addView(crumbSeparator());
            breadcrumbRow.addView(crumb(part, path.toString()));
        }
    }

    private View crumb(String label, final String path) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(12.5f);
        boolean current = path.equals(folder);
        view.setTextColor(current
                ? Ui.themeColor(this, com.google.android.material.R.attr.colorOnSurface)
                : Ui.themeColor(this, com.google.android.material.R.attr.colorPrimary));
        Ui.setPaddingDp(view, 6, 4, 6, 4);
        view.setBackground(Ui.rippleRect(this,
                Ui.withAlpha(Ui.themeColor(this,
                        com.google.android.material.R.attr.colorOnSurface), 0.1f),
                Ui.dp(this, 6), 0x00000000));
        view.setOnClickListener(v -> {
            folder = path;
            prefs.setLastFolder(folder);
            rebuild();
        });
        view.setOnDragListener((target, event) -> {
            android.content.ClipDescription description = event.getClipDescription();
            boolean noteDrag = description != null
                    && "shard-note".contentEquals(description.getLabel());
            if (!noteDrag) return false;
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    target.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.72f)
                            .setDuration(100L).start();
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(120L).start();
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(120L).start();
                    android.content.ClipData data = event.getClipData();
                    if (data != null && data.getItemCount() > 0) {
                        onNoteDropped(data.getItemAt(0).coerceToText(this).toString(), path);
                    }
                    return true;
                default:
                    return true;
            }
        });
        return view;
    }

    private View crumbSeparator() {
        TextView view = new TextView(this);
        view.setText("›");
        view.setTextSize(12.5f);
        view.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        return view;
    }

    // ---------------------------------------------------------------- list callbacks

    @Override
    public void onNoteClicked(Note note) {
        NoteEditorActivity.open(this, note.getId());
    }

    @Override
    public void onNoteSelectionToggle(Note note) {
        if (note == null) return;
        if (!noteSelection.isActive()) {
            enterSelection(note);
            return;
        }
        boolean selected = noteSelection.toggle(note.getId());
        adapter.setSelected(note.getId(), selected);
        updateSelectionChrome();
        if (noteSelection.isEmpty()) exitSelection();
    }

    @Override
    public void onNoteMenu(View anchor, final Note note, float x, float y) {
        AnchoredMenu.vertical(this)
                .title(note.getTitle())
                .add(1, R.drawable.ic_open_in_new, getString(R.string.note_open))
                .add(9, R.drawable.ic_check, getString(R.string.note_select))
                .add(2, R.drawable.ic_pin, getString(note.isPinned()
                        ? R.string.note_unpin : R.string.note_pin))
                .add(3, note.isBookmarked()
                                ? R.drawable.ic_star : R.drawable.ic_star_outline,
                        getString(note.isBookmarked()
                                ? R.string.note_unstar : R.string.note_star))
                .divider()
                .add(4, R.drawable.ic_move, getString(R.string.note_move))
                .add(5, R.drawable.ic_copy, getString(R.string.note_duplicate))
                .add(6, R.drawable.ic_export, getString(R.string.export_note))
                .add(7, R.drawable.ic_archive, getString(note.isArchived()
                        ? R.string.note_unarchive : R.string.note_archive))
                .divider()
                .add(new AnchoredMenu.Item(8, R.drawable.ic_delete,
                        getString(R.string.note_delete)).destructive())
                .onItem(id -> handleNoteMenu(id, note))
                .showAtPoint(anchor, x, y);
    }

    private void handleNoteMenu(int id, Note note) {
        switch (id) {
            case 1:
                NoteEditorActivity.open(this, note.getId());
                break;
            case 9:
                enterSelection(note);
                break;
            case 2:
                note.setPinned(!note.isPinned());
                repo.updateMeta(note);
                break;
            case 3:
                note.setBookmarked(!note.isBookmarked());
                repo.updateMeta(note);
                break;
            case 4:
                showMoveDialog(note);
                break;
            case 5: {
                Note copy = repo.duplicate(note);
                if (copy != null) toast(copy.getTitle());
                break;
            }
            case 6:
                exportNote(note);
                break;
            case 7:
                note.setArchived(!note.isArchived());
                repo.updateMeta(note);
                break;
            case 8:
                confirmDeleteNote(note);
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------- bulk selection

    private void enterSelection(Note note) {
        if (note == null) return;
        noteSelection.start(note.getId());
        adapter.setSelectionMode(true);
        adapter.setSelectedIds(noteSelection.ids());
        updateSelectionChrome();
    }

    private void exitSelection() {
        noteSelection.clear();
        adapter.setSelectionMode(false);
        updateSelectionChrome();
    }

    private void updateSelectionChrome() {
        if (selectionBar == null || homeToolbar == null) return;
        homeToolbar.setVisibility(noteSelection.isActive() ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(noteSelection.isActive() ? View.VISIBLE : View.GONE);
        if (noteSelection.isActive()) {
            selectionCount.setText(getString(R.string.selected_notes_count,
                    noteSelection.size()));
        }
    }

    private void toggleSelectAllVisible() {
        if (!noteSelection.isActive()) return;
        noteSelection.toggleAll(adapter.noteIds());
        adapter.setSelectedIds(noteSelection.ids());
        updateSelectionChrome();
        if (noteSelection.isEmpty()) exitSelection();
    }

    private List<String> selectedIds() {
        return noteSelection.ids();
    }

    private List<Note> selectedNotes() {
        List<Note> notes = new ArrayList<>(noteSelection.size());
        for (String id : noteSelection.ids()) {
            Note note = repo.meta(id);
            if (note != null) notes.add(note);
        }
        return notes;
    }

    private void showBulkActions(View anchor) {
        if (noteSelection.isEmpty()) return;
        boolean allArchived = true;
        for (Note note : selectedNotes()) {
            if (!note.isArchived()) {
                allArchived = false;
                break;
            }
        }
        final boolean archiveNext = !allArchived;
        AnchoredMenu.vertical(this)
                .title(getString(R.string.selected_notes_count, noteSelection.size()))
                .add(1, R.drawable.ic_move, getString(R.string.bulk_move))
                .add(2, R.drawable.ic_tag, getString(R.string.bulk_add_tag))
                .add(3, R.drawable.ic_tag, getString(R.string.bulk_remove_tag))
                .add(4, R.drawable.ic_archive, getString(allArchived
                        ? R.string.bulk_unarchive : R.string.bulk_archive))
                .add(5, R.drawable.ic_filter, getString(R.string.bulk_collection))
                .add(6, R.drawable.ic_export, getString(R.string.bulk_export))
                .divider()
                .add(new AnchoredMenu.Item(7, R.drawable.ic_delete,
                        getString(R.string.bulk_delete)).destructive())
                .onItem(id -> {
                    switch (id) {
                        case 1: showBulkMoveDialog(); break;
                        case 2: showBulkTagDialog(true); break;
                        case 3: showBulkTagDialog(false); break;
                        case 4: applyBulkArchive(archiveNext); break;
                        case 5: showBulkCollectionDialog(); break;
                        case 6: exportSelectedNotes(); break;
                        case 7: confirmBulkDelete(); break;
                        default: break;
                    }
                })
                .showAt(anchor);
    }

    private void showBulkMoveDialog() {
        final List<String> ids = selectedIds();
        final List<String> folders = new ArrayList<>();
        folders.add("");
        folders.addAll(repo.index().allFolders());
        CharSequence[] labels = new CharSequence[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = folders.get(i).isEmpty()
                    ? getString(R.string.vault_root) : folders.get(i);
        }
        dialog()
                .setTitle(R.string.bulk_move)
                .setItems(labels, (d, which) -> {
                    exitSelection();
                    repo.bulkMove(ids, folders.get(which), new Io.Ok<Integer>() {
                        @Override public void onReady(Integer count) {
                            toast(getString(R.string.bulk_moved, count == null ? 0 : count));
                            rebuild();
                        }

                        @Override public void onError(Throwable t) {
                            toast(R.string.bulk_operation_failed);
                            rebuild();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showBulkTagDialog(final boolean add) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.bulk_tag_hint);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog()
                .setTitle(add ? R.string.bulk_add_tag : R.string.bulk_remove_tag)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String tag = Md.normalizeTag(input.getText().toString());
                    if (tag.isEmpty()) {
                        toast(R.string.bulk_invalid_tag);
                        return;
                    }
                    applyBulkTag(selectedIds(), tag, add);
                })
                .show();
    }

    private void applyBulkTag(final List<String> ids, final String tag, final boolean add) {
        exitSelection();
        repo.bulkEdit(ids, note -> {
            if (NoteFile.isTexFile(note.getId())) return false;
            String old = note.getContent();
            String updated = Md.withInlineTag(old, tag, add);
            if (old.equals(updated)) return false;
            note.setContent(updated);
            return true;
        }, new Io.Ok<Integer>() {
            @Override public void onReady(Integer count) {
                toast(getString(R.string.bulk_tagged, count == null ? 0 : count));
                rebuild();
            }

            @Override public void onError(Throwable t) {
                toast(R.string.bulk_operation_failed);
                rebuild();
            }
        });
    }

    private void applyBulkArchive(final boolean archive) {
        final List<String> ids = selectedIds();
        exitSelection();
        repo.bulkEdit(ids, note -> {
            if (note.isArchived() == archive) return false;
            note.setArchived(archive);
            return true;
        }, new Io.Ok<Integer>() {
            @Override public void onReady(Integer count) {
                toast(getString(archive ? R.string.bulk_archived : R.string.bulk_unarchived,
                        count == null ? 0 : count));
                rebuild();
            }

            @Override public void onError(Throwable t) {
                toast(R.string.bulk_operation_failed);
                rebuild();
            }
        });
    }

    private void showBulkCollectionDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.bulk_collection_hint);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog()
                .setTitle(R.string.bulk_collection)
                .setMessage(R.string.bulk_collection_message)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) ->
                        applyBulkCollection(selectedIds(), input.getText().toString().trim()))
                .show();
    }

    private void applyBulkCollection(final List<String> ids, final String collection) {
        exitSelection();
        repo.bulkEdit(ids, note -> {
            String before = NoteProperties.read(note).get("collection");
            if (collection.isEmpty()) {
                if (before == null) return false;
                NoteProperties.remove(note, "collection");
                return true;
            }
            if (collection.equals(before)) return false;
            return NoteProperties.put(note, "collection", collection);
        }, new Io.Ok<Integer>() {
            @Override public void onReady(Integer count) {
                toast(getString(R.string.bulk_collection_changed,
                        count == null ? 0 : count));
                rebuild();
            }

            @Override public void onError(Throwable t) {
                toast(R.string.bulk_operation_failed);
                rebuild();
            }
        });
    }

    private void exportSelectedNotes() {
        List<Note> notes = selectedNotes();
        exitSelection();
        exportFlow.offerNotes(notes, findViewById(R.id.btnSort));
    }

    private void confirmBulkDelete() {
        final List<String> ids = selectedIds();
        confirm(R.string.bulk_delete_title,
                getString(R.string.bulk_delete_body, ids.size(), prefs.trashRetentionDays()),
                R.string.note_delete,
                () -> {
                    exitSelection();
                    repo.bulkDelete(ids, new Io.Ok<Integer>() {
                        @Override public void onReady(Integer count) {
                            for (String id : ids) prefs.forgetRecent(id);
                            toast(getString(R.string.bulk_deleted, count == null ? 0 : count));
                            rebuild();
                        }

                        @Override public void onError(Throwable t) {
                            toast(R.string.bulk_operation_failed);
                            rebuild();
                        }
                    });
                });
    }

    private void confirmDeleteNote(final Note note) {
        Runnable delete = () -> {
            repo.delete(note);
            prefs.forgetRecent(note.getId());
            toast(R.string.moved_to_trash);
        };
        if (!prefs.confirmDelete()) {
            delete.run();
            return;
        }
        confirm(R.string.delete_note_q,
                getString(R.string.delete_note_body, note.getTitle(),
                        prefs.trashRetentionDays()),
                R.string.note_delete, delete);
    }

    private void showMoveDialog(final Note note) {
        final List<String> folders = new ArrayList<>();
        folders.add("");
        folders.addAll(repo.index().allFolders());
        CharSequence[] labels = new CharSequence[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = folders.get(i).isEmpty()
                    ? getString(R.string.vault_root) : folders.get(i);
        }
        dialog()
                .setTitle(R.string.note_move)
                .setItems(labels, (d, which) -> repo.move(note, folders.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void exportNote(final Note note) {
        exportFlow.offerNoteFormats(note, findViewById(R.id.btnSort));
    }

    @Override
    public void onFolderClicked(String path, String name) {
        view = VIEW_FOLDER;
        folder = path;
        prefs.setLastFolder(folder);
        rebuild();
    }

    @Override
    public void onFolderMenu(View anchor, final String path, final String name) {
        AnchoredMenu.vertical(this)
                .title(name)
                .add(1, R.drawable.ic_folder_open, getString(R.string.note_open))
                .add(2, R.drawable.ic_export, getString(R.string.folder_export))
                .add(3, R.drawable.ic_block_text, getString(R.string.folder_rename))
                .divider()
                .add(new AnchoredMenu.Item(4, R.drawable.ic_delete,
                        getString(R.string.folder_delete)).destructive())
                .onItem(id -> {
                    switch (id) {
                        case 1: onFolderClicked(path, name); break;
                        case 2:
                            exportFlow.exportFolder(path, name, anchor);
                            break;
                        case 3: promptRenameFolder(path, name); break;
                        case 4:
                            confirm(R.string.folder_delete,
                                    getString(R.string.folder_delete_body),
                                    R.string.folder_delete,
                                    () -> repo.deleteFolder(path, this::rebuild));
                            break;
                        default: break;
                    }
                })
                .showAt(anchor);
    }

    @Override
    public void onNoteDropped(String noteId, String folderPath) {
        Note note = repo.meta(noteId);
        if (note == null || folderPath == null || folderPath.equals(note.folder())) return;
        String moved = repo.move(note, folderPath);
        if (moved != null) toast(getString(R.string.note_moved_to_folder,
                folderPath.isEmpty() ? getString(R.string.vault_root) : folderPath));
    }

    private void promptRenameFolder(final String path, String current) {
        final EditText input = new EditText(this);
        input.setText(current);
        input.setHint(R.string.folder_name);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog()
                .setTitle(R.string.folder_rename)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) repo.renameFolder(path, name, this::rebuild);
                })
                .show();
    }

    // ---------------------------------------------------------------- swipe

    /** Swipe right pins, swipe left trashes — both with an undo path. */
    private void attachSwipeActions() {
        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback callback =
                new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0,
                        androidx.recyclerview.widget.ItemTouchHelper.LEFT
                                | androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
                    @Override
                    public boolean onMove(RecyclerView view, RecyclerView.ViewHolder holder,
                                          RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public int getSwipeDirs(RecyclerView view, RecyclerView.ViewHolder holder) {
                        VaultItem item = adapter.itemAt(holder.getBindingAdapterPosition());
                        return item != null && item.type == VaultItem.TYPE_NOTE
                                ? super.getSwipeDirs(view, holder) : 0;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
                        VaultItem item = adapter.itemAt(holder.getBindingAdapterPosition());
                        if (item == null || item.note == null) {
                            adapter.notifyDataSetChanged();
                            return;
                        }
                        final Note note = item.note;
                        if (direction == androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
                            note.setPinned(!note.isPinned());
                            repo.updateMeta(note);
                        } else {
                            repo.delete(note);
                            toastWithAction(getString(R.string.moved_to_trash),
                                    R.string.undo, () -> restoreLastTrashed(note.getId()));
                        }
                    }
                };
        new androidx.recyclerview.widget.ItemTouchHelper(callback).attachToRecyclerView(list);
    }

    private void restoreLastTrashed(String noteId) {
        for (VaultRepository.TrashEntry entry : repo.trash()) {
            if (entry.originalId.equals(noteId)) {
                repo.restoreFromTrash(entry, this::rebuild);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- sort menu

    private void showSortMenu(View anchor) {
        AnchoredMenu.vertical(this)
                .title(getString(R.string.sort_by))
                .add(new AnchoredMenu.Item(1, R.drawable.ic_recent,
                        getString(R.string.sort_modified))
                        .checked(prefs.sortMode() == Prefs.SORT_MODIFIED))
                .add(new AnchoredMenu.Item(2, R.drawable.ic_block_date,
                        getString(R.string.sort_created))
                        .checked(prefs.sortMode() == Prefs.SORT_CREATED))
                .add(new AnchoredMenu.Item(3, R.drawable.ic_block_text,
                        getString(R.string.sort_title))
                        .checked(prefs.sortMode() == Prefs.SORT_TITLE))
                .add(new AnchoredMenu.Item(4, R.drawable.ic_view_list,
                        getString(R.string.sort_size))
                        .checked(prefs.sortMode() == Prefs.SORT_SIZE))
                .divider()
                .add(new AnchoredMenu.Item(5, R.drawable.ic_sort,
                        getString(R.string.sort_reverse))
                        .checked(!prefs.sortDescending()))
                .add(new AnchoredMenu.Item(6, R.drawable.ic_folder,
                        getString(R.string.group_folders))
                        .checked(prefs.groupFolders()))
                .add(new AnchoredMenu.Item(7, R.drawable.ic_archive,
                        getString(R.string.show_archived))
                        .checked(prefs.showArchived()))
                .divider()
                .add(new AnchoredMenu.Item(20, R.drawable.ic_block_text,
                        getString(R.string.filter_block_type))
                        .hint(blockTypeName(blockTypeFilter)))
                .add(new AnchoredMenu.Item(21, R.drawable.ic_block_date,
                        getString(R.string.filter_date))
                        .hint(dateFilterDays == 0 ? getString(R.string.filter_all)
                                : getResources().getQuantityString(R.plurals.filter_last_days,
                                        dateFilterDays, dateFilterDays)))
                .onItem(id -> {
                    switch (id) {
                        case 1: prefs.setSortMode(Prefs.SORT_MODIFIED); break;
                        case 2: prefs.setSortMode(Prefs.SORT_CREATED); break;
                        case 3: prefs.setSortMode(Prefs.SORT_TITLE); break;
                        case 4: prefs.setSortMode(Prefs.SORT_SIZE); break;
                        case 5: prefs.setSortDescending(!prefs.sortDescending()); break;
                        case 6: prefs.setGroupFolders(!prefs.groupFolders()); break;
                        case 7: prefs.setShowArchived(!prefs.showArchived()); break;
                        case 20: showBlockTypeFilter(anchor); return;
                        case 21: showDateFilter(anchor); return;
                        default: break;
                    }
                    rebuild();
                })
                .showAt(anchor);
    }

    private void showBlockTypeFilter(View anchor) {
        final int[] masks = {0, Note.BLOCK_HEADING, Note.BLOCK_LIST, Note.BLOCK_TASK,
                Note.BLOCK_CODE, Note.BLOCK_TABLE, Note.BLOCK_IMAGE, Note.BLOCK_QUOTE};
        final int[] icons = {R.drawable.ic_notes, R.drawable.ic_block_h1,
                R.drawable.ic_block_bullet, R.drawable.ic_block_todo,
                R.drawable.ic_block_code, R.drawable.ic_block_table,
                R.drawable.ic_block_image, R.drawable.ic_block_quote};
        AnchoredMenu menu = AnchoredMenu.vertical(this).title(getString(R.string.filter_block_type));
        for (int i = 0; i < masks.length; i++) {
            menu.add(new AnchoredMenu.Item(i, icons[i], blockTypeName(masks[i]))
                    .checked(blockTypeFilter == masks[i]));
        }
        menu.onItem(id -> {
            if (id < 0 || id >= masks.length) return;
            blockTypeFilter = masks[id];
            rebuild();
        }).showAt(anchor);
    }

    private void showDateFilter(View anchor) {
        final int[] days = {0, 1, 7, 30, 365};
        AnchoredMenu menu = AnchoredMenu.vertical(this).title(getString(R.string.filter_date));
        for (int i = 0; i < days.length; i++) {
            String label = days[i] == 0 ? getString(R.string.filter_all)
                    : getResources().getQuantityString(R.plurals.filter_last_days, days[i], days[i]);
            menu.add(new AnchoredMenu.Item(i, R.drawable.ic_block_date, label)
                    .checked(dateFilterDays == days[i]));
        }
        menu.onItem(id -> {
            if (id < 0 || id >= days.length) return;
            dateFilterDays = days[id];
            rebuild();
        }).showAt(anchor);
    }

    private String blockTypeName(int mask) {
        if (mask == Note.BLOCK_HEADING) return getString(R.string.filter_headings);
        if (mask == Note.BLOCK_LIST) return getString(R.string.filter_lists);
        if (mask == Note.BLOCK_TASK) return getString(R.string.filter_tasks);
        if (mask == Note.BLOCK_CODE) return getString(R.string.filter_code);
        if (mask == Note.BLOCK_TABLE) return getString(R.string.filter_tables);
        if (mask == Note.BLOCK_IMAGE) return getString(R.string.filter_images);
        if (mask == Note.BLOCK_QUOTE) return getString(R.string.filter_quotes);
        return getString(R.string.filter_all);
    }

    // ---------------------------------------------------------------- drawer

    private void buildDrawer() {
        vaultDrawer.rebuild();
    }

    private void selectView(int target) {
        view = target;
        tagFilter = null;
        drawer.closeDrawer(GravityCompat.START);
        rebuild();
    }

    private void openToday() {
        drawer.closeDrawer(GravityCompat.START);
        if (!ensureDocumentsVault()) return;
        DailyNotes.open(this, repo, new Date());
    }

    private void showDailyCalendar() {
        drawer.closeDrawer(GravityCompat.START);
        if (!ensureDocumentsVault()) return;
        startActivity(new Intent(this, CalendarActivity.class));
    }

    private void showCommandPalette() {
        List<CommandPalette.Action> actions = new ArrayList<>();
        actions.add(new CommandPalette.Action(R.drawable.ic_add,
                getString(R.string.command_new_note), "new create", this::createMarkdownNote));
        actions.add(new CommandPalette.Action(R.drawable.ic_block_code,
                getString(R.string.command_new_tex), "latex tex source", this::createTexNote));
        actions.add(new CommandPalette.Action(R.drawable.ic_block_date,
                getString(R.string.nav_daily_note), "today daily journal", this::openToday));
        actions.add(new CommandPalette.Action(R.drawable.ic_recent,
                getString(R.string.nav_calendar), "date daily journal", this::showDailyCalendar));
        actions.add(new CommandPalette.Action(R.drawable.ic_checkbox,
                getString(R.string.nav_tasks), "todo due deadline", () ->
                startActivity(new Intent(this, TasksActivity.class))));
        actions.add(new CommandPalette.Action(R.drawable.ic_filter,
                getString(R.string.nav_collections), "properties yaml database", () ->
                startActivity(new Intent(this, CollectionsActivity.class))));
        actions.add(new CommandPalette.Action(R.drawable.ic_search,
                getString(R.string.command_focus_search), "find", () -> {
                    searchInput.requestFocus();
                    Ui.showKeyboard(searchInput);
                }));
        actions.add(new CommandPalette.Action(R.drawable.ic_notes,
                getString(R.string.nav_all_notes), "library", () -> selectView(VIEW_ALL)));
        actions.add(new CommandPalette.Action(R.drawable.ic_star_outline,
                getString(R.string.nav_favourites), "starred", () -> selectView(VIEW_FAVOURITES)));
        actions.add(new CommandPalette.Action(R.drawable.ic_graph,
                getString(R.string.nav_graph), "links", () ->
                startActivity(new Intent(this, GraphActivity.class))));
        actions.add(new CommandPalette.Action(R.drawable.ic_canvas,
                getString(R.string.nav_canvas), "board", () -> CanvasActivity.start(this)));
        actions.add(new CommandPalette.Action(R.drawable.ic_import,
                getString(R.string.nav_import), "files backup", () -> showImportMenu(fab)));
        actions.add(new CommandPalette.Action(R.drawable.ic_backup,
                getString(R.string.nav_export_vault), "zip export", this::backupVault));
        actions.add(new CommandPalette.Action(R.drawable.ic_settings,
                getString(R.string.command_open_settings), "preferences", () ->
                startActivity(new Intent(this, SettingsActivity.class))));
        CommandPalette.show(this, actions);
    }

    private void showTagPicker() {
        final List<String> tags = repo.index().allTags();
        if (tags.isEmpty()) {
            toast(R.string.no_tags);
            return;
        }
        java.util.Map<String, Integer> counts = repo.index().tagCounts();
        AnchoredMenu menu = AnchoredMenu.vertical(this).title(getString(R.string.tags_title));
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            Integer count = counts.get(tag);
            menu.add(new AnchoredMenu.Item(i, R.drawable.ic_tag, "#" + tag)
                    .hint(count == null ? null : String.valueOf(count)));
        }
        menu.onItem(id -> {
            if (id < 0 || id >= tags.size()) return;
            view = VIEW_TAG;
            tagFilter = tags.get(id);
            drawer.closeDrawer(GravityCompat.START);
            rebuild();
        }).showAt(drawerContent);
    }

    private void promptNewFolder() {
        final EditText input = new EditText(this);
        input.setHint(R.string.folder_name);
        int pad = Ui.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog()
                .setTitle(R.string.new_folder_title)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (repo.createFolder(view == VIEW_FOLDER ? folder : "", name)) {
                        drawer.closeDrawer(GravityCompat.START);
                        rebuild();
                        buildDrawer();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- import/export

    /** Bridges {@link ImportFlow} to this screen's folder context and progress bar. */
    private final class ImportHost implements ImportFlow.Host {
        @Override public String targetFolder() { return currentFolderForNew(); }

        @Override public void setBusy(boolean busy) { refresh.setRefreshing(busy); }

        @Override public void onImported() {
            rebuild();
            buildDrawer();
        }

        @Override public boolean canImport() { return ensureDocumentsVault(); }

        @Override public void dismissChrome() {
            drawer.closeDrawer(GravityCompat.START);
        }
    }

    /** Bridges {@link VaultDrawer} to this screen's browsing state. */
    private final class DrawerHost implements VaultDrawer.Host {
        @Override public void showAllNotes() { selectView(VIEW_ALL); }

        @Override public void showFavourites() { selectView(VIEW_FAVOURITES); }

        @Override public void showRecent() { selectView(VIEW_RECENT); }

        @Override public void openToday() { HomeActivity.this.openToday(); }

        @Override public void showCalendar() { showDailyCalendar(); }

        @Override public void showTagPicker() { HomeActivity.this.showTagPicker(); }

        @Override public void openFolder(String path) {
        view = VIEW_FOLDER;
        folder = path;
        prefs.setLastFolder(folder);
        closeDrawer();
            rebuild();
        }

        @Override public void moveNoteToFolder(String noteId, String folderPath) {
            HomeActivity.this.onNoteDropped(noteId, folderPath);
            closeDrawer();
        }

        @Override public void promptNewFolder() { HomeActivity.this.promptNewFolder(); }

        @Override public void showImportMenu(View anchor) {
            HomeActivity.this.showImportMenu(anchor);
        }

        @Override public void backupVault() { HomeActivity.this.backupVault(); }

        @Override public void closeDrawer() { drawer.closeDrawer(GravityCompat.START); }
    }

    private void showImportMenu(View anchor) {
        importFlow.showSourceMenu(anchor);
    }

    private void backupVault() {
        drawer.closeDrawer(GravityCompat.START);
        // A ZIP of the whole vault is what makes the app safe to leave: the user
        // can always walk away with plain Markdown files.
        exportFlow.exportVault(findViewById(R.id.btnSort),
                () -> toast(R.string.backup_created));
    }
}
