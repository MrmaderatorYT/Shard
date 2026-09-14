package com.ccs.shard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.widget.Toolbar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.canvas.CanvasDoc;
import com.ccs.shard.canvas.CanvasTemplates;
import com.ccs.shard.canvas.CanvasView;
import com.ccs.shard.core.CanvasStore;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.InlineTextPrompt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Editor for persisted JSON Canvas files in the vault.
 *
 * <p>The view owns gestures and drawing; this activity owns file lifecycle. Every
 * edit is serialised on the main thread and written through the shared serial disk
 * executor after a short debounce. Switching canvases, pausing the activity, or
 * renaming a file flushes the pending edit first, so a quick back gesture cannot
 * lose the last card move.
 */
public final class CanvasActivity extends BaseActivity implements CanvasView.Listener {

    public static final String EXTRA_CANVAS_ID = "canvas_id";

    private static final int MENU_CANVASES = 1;
    private static final int MENU_MORE = 2;
    private static final int MENU_SEARCH = 3;
    private static final long SAVE_DELAY_MS = 450L;

    private Toolbar toolbar;
    private CanvasView canvasView;
    private CanvasStore store;
    private View addTextButton;
    private View addNoteButton;
    private View addImageButton;
    private View drawButton;
    private View selectButton;
    private ActivityResultLauncher<String> imagePicker;
    private float pendingImageX;
    private float pendingImageY;

    private String canvasId;
    private boolean dirty;
    private int loadGeneration;

    private final Runnable delayedSave = new Runnable() {
        @Override public void run() { saveNow(); }
    };

    public static void start(Context context) {
        context.startActivity(new Intent(context, CanvasActivity.class));
    }

    public static void open(Context context, String canvasId) {
        Intent intent = new Intent(context, CanvasActivity.class);
        intent.putExtra(EXTRA_CANVAS_ID, canvasId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_canvas);

        store = new CanvasStore(repo.vault());
        toolbar = findViewById(R.id.toolbar);
        canvasView = findViewById(R.id.canvasView);
        addTextButton = findViewById(R.id.btnAddText);
        addNoteButton = findViewById(R.id.btnAddLink);
        addImageButton = findViewById(R.id.btnAddCanvasImage);
        drawButton = findViewById(R.id.btnCanvasDraw);
        selectButton = findViewById(R.id.btnCanvasSelect);
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImagePicked);

        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnClickListener(v -> showCanvasPicker());
        installToolbarMenu();

        canvasView.setListener(this);
        canvasView.setTitleResolver(this::noteTitleOf);

        addTextButton.setOnClickListener(v -> {
            float[] centre = canvasView.viewportCentreWorld();
            promptText(centre[0], centre[1], addTextButton, 0, addTextButton.getHeight());
        });
        addNoteButton.setOnClickListener(v -> {
            float[] centre = canvasView.viewportCentreWorld();
            showNotePicker(centre[0], centre[1]);
        });
        addImageButton.setOnClickListener(v -> {
            float[] centre = canvasView.viewportCentreWorld();
            pickImage(centre[0] - 140f, centre[1] - 95f);
        });
        drawButton.setOnClickListener(v -> {
            canvasView.setDrawingMode(!canvasView.isDrawingMode());
            updateCanvasModeButtons();
        });
        selectButton.setOnClickListener(v -> {
            canvasView.setMultiSelectMode(!canvasView.isMultiSelectMode());
            updateCanvasModeButtons();
        });
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> canvasView.zoomBy(1.25f));
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> canvasView.zoomBy(0.8f));
        findViewById(R.id.btnReset).setOnClickListener(v -> canvasView.fitToContent());
        updateCanvasModeButtons();

        toolbar.setTitle(R.string.canvas_loading);
        setEditorEnabled(false);
        final String requested = getIntent().getStringExtra(EXTRA_CANVAS_ID);
        repo.open(() -> openInitialCanvas(requested));
    }

    private void installToolbarMenu() {
        Menu menu = toolbar.getMenu();
        menu.clear();
        menu.add(Menu.NONE, MENU_CANVASES, Menu.NONE, R.string.canvas_switch)
                .setIcon(R.drawable.ic_canvas)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(Menu.NONE, MENU_MORE, Menu.NONE, R.string.canvas_options)
                .setIcon(R.drawable.ic_menu_dots)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, R.string.canvas_search)
                .setIcon(R.drawable.ic_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_CANVASES) {
                showCanvasPicker();
                return true;
            }
            if (item.getItemId() == MENU_MORE) {
                showCanvasOptions(toolbar);
                return true;
            }
            if (item.getItemId() == MENU_SEARCH) {
                showCanvasSearch();
                return true;
            }
            return false;
        });
    }

    private void setEditorEnabled(boolean enabled) {
        canvasView.setEnabled(enabled);
        addTextButton.setEnabled(enabled);
        addNoteButton.setEnabled(enabled);
        addImageButton.setEnabled(enabled);
        drawButton.setEnabled(enabled);
        selectButton.setEnabled(enabled);
        findViewById(R.id.btnZoomIn).setEnabled(enabled);
        findViewById(R.id.btnZoomOut).setEnabled(enabled);
        findViewById(R.id.btnReset).setEnabled(enabled);
    }

    private void updateCanvasModeButtons() {
        if (drawButton == null || selectButton == null) return;
        drawButton.setSelected(canvasView.isDrawingMode());
        selectButton.setSelected(canvasView.isMultiSelectMode());
        drawButton.setAlpha(canvasView.isDrawingMode() ? 1f : 0.72f);
        selectButton.setAlpha(canvasView.isMultiSelectMode() ? 1f : 0.72f);
    }

    // ---------------------------------------------------------------- files

    private static final class LoadedCanvas {
        final String id;
        final CanvasDoc document;

        LoadedCanvas(String id, CanvasDoc document) {
            this.id = id;
            this.document = document;
        }
    }

    /** Opens an explicitly requested canvas, the newest canvas, or a new default. */
    private void openInitialCanvas(final String requestedId) {
        final String defaultTitle = getString(R.string.canvas_default_title);
        final int generation = ++loadGeneration;
        Io.load(new Io.Task<LoadedCanvas>() {
            @Override public LoadedCanvas run() throws Exception {
                String id = store.contains(requestedId) ? requestedId : null;
                if (id == null) {
                    List<CanvasStore.Entry> entries = store.list();
                    if (!entries.isEmpty()) id = entries.get(0).id;
                }
                if (id == null) id = store.create(defaultTitle, "");
                return new LoadedCanvas(id, CanvasDoc.parse(store.read(id)));
            }
        }, new Io.Result<LoadedCanvas>() {
            @Override public void onReady(LoadedCanvas loaded) {
                if (generation != loadGeneration || gone()) return;
                showLoadedCanvas(loaded);
            }

            @Override public void onError(Throwable error) {
                if (generation != loadGeneration || gone()) return;
                toolbar.setTitle(R.string.nav_canvas);
                setEditorEnabled(false);
                toast(R.string.canvas_load_failed);
            }
        });
    }

    private void loadCanvas(final String id) {
        if (id == null || id.equals(canvasId)) return;
        saveNow();
        setEditorEnabled(false);
        toolbar.setTitle(R.string.canvas_loading);
        final int generation = ++loadGeneration;
        Io.load(new Io.Task<LoadedCanvas>() {
            @Override public LoadedCanvas run() throws Exception {
                if (!store.contains(id)) throw new IOException("Canvas no longer exists");
                return new LoadedCanvas(id, CanvasDoc.parse(store.read(id)));
            }
        }, new Io.Result<LoadedCanvas>() {
            @Override public void onReady(LoadedCanvas loaded) {
                if (generation != loadGeneration || gone()) return;
                showLoadedCanvas(loaded);
            }

            @Override public void onError(Throwable error) {
                if (generation != loadGeneration || gone()) return;
                setEditorEnabled(canvasId != null);
                updateTitle();
                toast(R.string.canvas_load_failed);
            }
        });
    }

    private void showLoadedCanvas(LoadedCanvas loaded) {
        canvasId = loaded.id;
        dirty = false;
        canvasView.setDocument(loaded.document);
        updateCanvasModeButtons();
        setEditorEnabled(true);
        updateTitle();
    }

    private void updateTitle() {
        toolbar.setTitle(canvasId == null
                ? getString(R.string.nav_canvas)
                : CanvasStore.titleOf(canvasId));
        toolbar.setSubtitle(canvasId != null && canvasId.indexOf('/') >= 0
                ? canvasId.substring(0, canvasId.lastIndexOf('/')) : null);
    }

    private void scheduleSave() {
        if (canvasId == null) return;
        dirty = true;
        Io.cancelMain(delayedSave);
        Io.onMainDelayed(delayedSave, SAVE_DELAY_MS);
    }

    /** Captures JSON before leaving the main thread, avoiding concurrent model access. */
    private void saveNow() {
        Io.cancelMain(delayedSave);
        if (!dirty || canvasId == null) return;
        final String id = canvasId;
        final String json = canvasView.document().toJson();
        dirty = false;
        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    store.write(id, json);
                } catch (Throwable error) {
                    Io.onMain(() -> {
                        if (!gone()) toast(R.string.canvas_save_failed);
                    });
                }
            }
        });
    }

    @Override
    protected void onPause() {
        saveNow();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Io.cancelMain(delayedSave);
        canvasView.setListener(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- canvas menus

    private void showCanvasPicker() {
        saveNow();
        Io.load(new Io.Task<List<CanvasStore.Entry>>() {
            @Override public List<CanvasStore.Entry> run() { return store.list(); }
        }, new Io.Ok<List<CanvasStore.Entry>>() {
            @Override public void onReady(final List<CanvasStore.Entry> entries) {
                if (gone()) return;
                CharSequence[] labels = new CharSequence[entries.size()];
                int selected = -1;
                for (int i = 0; i < entries.size(); i++) {
                    CanvasStore.Entry entry = entries.get(i);
                    String folder = folderOf(entry.id);
                    labels[i] = folder.isEmpty() ? entry.title : entry.title + "  ·  " + folder;
                    if (entry.id.equals(canvasId)) selected = i;
                }
                final androidx.appcompat.app.AlertDialog picker = dialog()
                        .setTitle(R.string.canvas_switch)
                        .setSingleChoiceItems(labels, selected, null)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.canvas_new, null)
                        .create();
                picker.setOnShowListener(ignored -> {
                    picker.getListView().setOnItemClickListener((parent, view, position, id) -> {
                        picker.dismiss();
                        loadCanvas(entries.get(position).id);
                    });
                    picker.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(v -> {
                                picker.dismiss();
                                promptNewCanvas();
                            });
                });
                picker.show();
            }
        });
    }

    private void showCanvasOptions(View anchor) {
        AnchoredMenu.vertical(this)
                .title(getString(R.string.canvas_options))
                .add(1, R.drawable.ic_add, getString(R.string.canvas_new))
                .add(2, R.drawable.ic_canvas, getString(R.string.canvas_switch))
                .add(3, R.drawable.ic_block_text, getString(R.string.rename))
                .divider()
                .add(4, R.drawable.ic_clear, getString(R.string.canvas_clear))
                .add(new AnchoredMenu.Item(5, R.drawable.ic_delete,
                        getString(R.string.canvas_delete)).destructive())
                .onItem(id -> {
                    switch (id) {
                        case 1: promptNewCanvas(); break;
                        case 2: showCanvasPicker(); break;
                        case 3: promptRenameCanvas(); break;
                        case 4: confirmClearCanvas(); break;
                        case 5: confirmDeleteCanvas(); break;
                        default: break;
                    }
                })
                .showAt(anchor);
    }

    private void promptNewCanvas() {
        promptCanvasTitle(R.string.canvas_new, "", title -> {
            final String value = title.trim();
            if (value.isEmpty()) return;
            showTemplatePicker(value);
        });
    }

    private void showTemplatePicker(final String title) {
        final String[] keys = {CanvasTemplates.BLANK, CanvasTemplates.KANBAN,
                CanvasTemplates.PROJECT, CanvasTemplates.MIND_MAP};
        CharSequence[] labels = {getString(R.string.canvas_template_blank),
                getString(R.string.canvas_template_kanban),
                getString(R.string.canvas_template_project),
                getString(R.string.canvas_template_mind_map)};
        dialog().setTitle(R.string.canvas_templates)
                .setItems(labels, (dialog, which) -> createCanvas(title, keys[which]))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void createCanvas(final String title, final String template) {
        saveNow();
        Io.load(new Io.Task<String>() {
            @Override public String run() throws Exception {
                String id = store.create(title, "");
                if (!CanvasTemplates.BLANK.equals(template)) {
                    store.write(id, CanvasTemplates.create(template).toJson());
                }
                return id;
            }
        }, new Io.Result<String>() {
            @Override public void onReady(String id) { loadCanvas(id); }
            @Override public void onError(Throwable error) { toast(R.string.canvas_create_failed); }
        });
    }

    private void promptRenameCanvas() {
        if (canvasId == null) return;
        final String oldId = canvasId;
        promptCanvasTitle(R.string.rename, CanvasStore.titleOf(oldId), title -> {
            final String value = title.trim();
            if (value.isEmpty() || value.equals(CanvasStore.titleOf(oldId))) return;
            saveNow();
            Io.load(new Io.Task<String>() {
                @Override public String run() { return store.rename(oldId, value); }
            }, new Io.Ok<String>() {
                @Override public void onReady(String newId) {
                    if (newId == null) {
                        toast(R.string.canvas_name_conflict);
                        return;
                    }
                    if (oldId.equals(canvasId)) {
                        canvasId = newId;
                        updateTitle();
                    }
                }
            });
        });
    }

    private void promptCanvasTitle(int titleRes, String initial, InlineTextPrompt.OnResult result) {
        InlineTextPrompt.show(toolbar, getString(titleRes), initial, false,
                Math.max(0, toolbar.getWidth() - 300), toolbar.getHeight(), result);
    }

    private void confirmClearCanvas() {
        if (canvasId == null || canvasView.document().isEmpty()) return;
        confirm(R.string.canvas_clear_q, getString(R.string.canvas_clear_body),
                R.string.canvas_clear, () -> canvasView.clearDocument());
    }

    private void confirmDeleteCanvas() {
        if (canvasId == null) return;
        final String deletingId = canvasId;
        confirm(R.string.canvas_delete_q,
                getString(R.string.canvas_delete_body, CanvasStore.titleOf(deletingId)),
                R.string.delete, () -> {
                    Io.cancelMain(delayedSave);
                    dirty = false;
                    canvasId = null;
                    setEditorEnabled(false);
                    toolbar.setTitle(R.string.canvas_loading);
                    Io.load(new Io.Task<Boolean>() {
                        @Override public Boolean run() { return store.delete(deletingId); }
                    }, new Io.Result<Boolean>() {
                        @Override public void onReady(Boolean deleted) {
                            if (!deleted) toast(R.string.canvas_delete_failed);
                            openInitialCanvas(null);
                        }
                        @Override public void onError(Throwable error) {
                            toast(R.string.canvas_delete_failed);
                            openInitialCanvas(null);
                        }
                    });
                });
    }

    // ---------------------------------------------------------------- cards

    @Override
    public void onSelectionChanged(CanvasDoc.Node node) {
        updateCanvasModeButtons();
    }

    @Override
    public void onNodeMenu(final CanvasDoc.Node node, float screenX, float screenY) {
        if (canvasView.selectedNodes().size() > 1) {
            showMultiNodeMenu(screenX, screenY);
            return;
        }
        AnchoredMenu menu = AnchoredMenu.vertical(this)
                .title(node.isGroup() ? node.label
                        : node.isFile() ? noteTitleOf(node.file)
                        : getString(R.string.canvas_text_card));
        if (node.isGroup()) {
            menu.add(1, R.drawable.ic_block_text, getString(R.string.canvas_edit_group));
        } else if (node.isFile() && !isImagePath(node.file)) {
            menu.add(1, R.drawable.ic_open_in_new, getString(R.string.note_open));
        } else if (!node.isFile()) {
            menu.add(1, R.drawable.ic_block_text, getString(R.string.edit));
            if (node.note != null && !node.note.isEmpty()) {
                menu.add(7, R.drawable.ic_open_in_new, getString(R.string.canvas_open_linked_note));
                menu.add(8, R.drawable.ic_wiki_link, getString(R.string.canvas_unlink_note));
            } else {
                menu.add(8, R.drawable.ic_wiki_link, getString(R.string.canvas_link_note));
            }
        }
        menu.add(2, R.drawable.ic_block_link, getString(R.string.canvas_connect))
                .add(4, R.drawable.ic_palette, getString(R.string.canvas_color))
                .add(5, R.drawable.ic_block_link, getString(R.string.canvas_edge_label))
                .add(6, R.drawable.ic_select_all, getString(R.string.canvas_resize))
                .divider()
                .add(new AnchoredMenu.Item(3, R.drawable.ic_delete,
                        getString(R.string.delete)).destructive())
                .onItem(id -> {
                    switch (id) {
                        case 1:
                            if (node.isGroup()) promptEditGroup(node, screenX, screenY);
                            else if (node.isFile() && !isImagePath(node.file)) openNote(node);
                            else promptEditNode(node, screenX, screenY);
                            break;
                        case 2:
                            canvasView.setConnectMode(true);
                            toast(R.string.canvas_connect_hint);
                            break;
                        case 3:
                            canvasView.removeNode(node);
                            break;
                        case 4:
                            showColorMenu(node, screenX, screenY);
                            break;
                        case 5:
                            showEdgeLabelPicker(node, screenX, screenY);
                            break;
                        case 6:
                            showSizeMenu(node, screenX, screenY);
                            break;
                        case 7:
                            openLinkedNote(node);
                            break;
                        case 8:
                            if (node.note != null && !node.note.isEmpty()) {
                                canvasView.setNoteLinkForSelection("");
                            } else {
                                showNoteLinkPicker();
                            }
                            break;
                        default: break;
                    }
                });
        menu.showAtPoint(canvasView, screenX, screenY);
    }

    private void showMultiNodeMenu(float screenX, float screenY) {
        final int count = canvasView.selectedNodes().size();
        AnchoredMenu.vertical(this)
                .title(getString(R.string.canvas_selected_cards, count))
                .add(1, R.drawable.ic_align_center, getString(R.string.canvas_auto_align))
                .add(2, R.drawable.ic_canvas, getString(R.string.canvas_group_selection))
                .add(3, R.drawable.ic_wiki_link, getString(R.string.canvas_link_note))
                .add(4, R.drawable.ic_clear, getString(R.string.canvas_unlink_note))
                .divider()
                .add(new AnchoredMenu.Item(5, R.drawable.ic_delete,
                        getString(R.string.delete)).destructive())
                .onItem(id -> {
                    switch (id) {
                        case 1: canvasView.alignSelection(); break;
                        case 2: promptGroupSelection(screenX, screenY); break;
                        case 3: showNoteLinkPicker(); break;
                        case 4: canvasView.setNoteLinkForSelection(""); break;
                        case 5: canvasView.removeSelectedNodes(); break;
                        default: break;
                    }
                }).showAtPoint(canvasView, screenX, screenY);
    }

    @Override
    public void onEmptyMenu(float worldX, float worldY, float screenX, float screenY) {
        final float x = worldX;
        final float y = worldY;
        AnchoredMenu.vertical(this)
                .title(getString(R.string.nav_canvas))
                .add(1, R.drawable.ic_block_text, getString(R.string.canvas_add_text))
                .add(2, R.drawable.ic_wiki_link, getString(R.string.canvas_add_note))
                .add(3, R.drawable.ic_block_image, getString(R.string.canvas_add_image))
                .add(4, R.drawable.ic_canvas, getString(R.string.canvas_add_group))
                .onItem(id -> {
                    if (id == 1) promptText(x, y, canvasView, screenX, screenY);
                    else if (id == 2) showNotePicker(x, y);
                    else if (id == 3) pickImage(x - 140f, y - 95f);
                    else if (id == 4) promptGroup(x, y, screenX, screenY);
                })
                .showAtPoint(canvasView, screenX, screenY);
    }

    @Override
    public void onNodeActivated(CanvasDoc.Node node) {
        if (node.isGroup()) promptEditGroup(node,
                canvasView.getWidth() / 2f, canvasView.getHeight() / 2f);
        else if (node.isFile() && !isImagePath(node.file)) openNote(node);
        else if (node.isFile()) return;
        else promptEditNode(node, canvasView.getWidth() / 2f, canvasView.getHeight() / 2f);
    }

    @Override
    public void onDocumentEdited() {
        scheduleSave();
    }

    private void promptText(float centreX, float centreY, View anchor, float promptX, float promptY) {
        InlineTextPrompt.show(anchor, getString(R.string.canvas_add_text), "", true,
                promptX, promptY, text -> {
                    if (text.trim().isEmpty()) return;
                    canvasView.addTextNode(text, centreX - 130f, centreY - 60f);
                });
    }

    private void promptEditNode(final CanvasDoc.Node node, float screenX, float screenY) {
        if (node.isFile() || node.isGroup()) return;
        InlineTextPrompt.show(canvasView, getString(R.string.canvas_edit_text), node.text,
                true, screenX, screenY, text -> canvasView.updateTextNode(node, text));
    }

    private void promptGroup(float x, float y, float screenX, float screenY) {
        InlineTextPrompt.show(canvasView, getString(R.string.canvas_add_group), "", false,
                screenX, screenY, label -> canvasView.addGroupNode(label,
                        x - 260f, y - 160f));
    }

    private void promptGroupSelection(float screenX, float screenY) {
        InlineTextPrompt.show(canvasView, getString(R.string.canvas_group_selection), "", false,
                screenX, screenY, canvasView::groupSelection);
    }

    private void promptEditGroup(CanvasDoc.Node node, float screenX, float screenY) {
        InlineTextPrompt.show(canvasView, getString(R.string.canvas_edit_group), node.label,
                false, screenX, screenY, label -> canvasView.updateGroupNode(node, label));
    }

    private void showColorMenu(CanvasDoc.Node node, float screenX, float screenY) {
        String[] names = {getString(R.string.canvas_color_default),
                getString(R.string.canvas_color_red), getString(R.string.canvas_color_orange),
                getString(R.string.canvas_color_yellow), getString(R.string.canvas_color_green),
                getString(R.string.canvas_color_blue), getString(R.string.canvas_color_purple)};
        AnchoredMenu menu = AnchoredMenu.vertical(this).title(getString(R.string.canvas_color));
        for (int i = 0; i < names.length; i++) {
            String value = i == 0 ? "" : String.valueOf(i);
            menu.add(new AnchoredMenu.Item(i, R.drawable.ic_palette, names[i])
                    .checked(value.equals(node.color)));
        }
        menu.onItem(id -> canvasView.setNodeColor(node,
                id <= 0 ? "" : String.valueOf(id)))
                .showAtPoint(canvasView, screenX, screenY);
    }

    private void showSizeMenu(CanvasDoc.Node node, float screenX, float screenY) {
        AnchoredMenu.vertical(this)
                .title(getString(R.string.canvas_resize))
                .add(1, R.drawable.ic_select_all, getString(R.string.canvas_size_small))
                .add(2, R.drawable.ic_select_all, getString(R.string.canvas_size_medium))
                .add(3, R.drawable.ic_select_all, getString(R.string.canvas_size_large))
                .onItem(id -> {
                    if (id == 1) { node.width = 200f; node.height = 100f; }
                    else if (id == 2) { node.width = 300f; node.height = 180f; }
                    else { node.width = node.isGroup() ? 640f : 440f;
                        node.height = node.isGroup() ? 400f : 280f; }
                    canvasView.invalidateNode(node);
                    onDocumentEdited();
                }).showAtPoint(canvasView, screenX, screenY);
    }

    private void showEdgeLabelPicker(CanvasDoc.Node node, float screenX, float screenY) {
        List<CanvasDoc.Edge> edges = canvasView.document().edgesOf(node);
        if (edges.isEmpty()) {
            toast(R.string.canvas_no_connections);
            return;
        }
        if (edges.size() == 1) {
            promptEdgeLabel(edges.get(0), screenX, screenY);
            return;
        }
        CharSequence[] labels = new CharSequence[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            CanvasDoc.Edge edge = edges.get(i);
            CanvasDoc.Node other = canvasView.document().nodeById(
                    node.id.equals(edge.fromNode) ? edge.toNode : edge.fromNode);
            labels[i] = other == null ? "?" : nodeLabel(other);
        }
        dialog().setTitle(R.string.canvas_choose_connection)
                .setItems(labels, (d, which) -> promptEdgeLabel(
                        edges.get(which), screenX, screenY))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void promptEdgeLabel(CanvasDoc.Edge edge, float screenX, float screenY) {
        InlineTextPrompt.show(canvasView, getString(R.string.canvas_edge_label), edge.label,
                false, screenX, screenY, label -> canvasView.updateEdgeLabel(edge, label));
    }

    private String nodeLabel(CanvasDoc.Node node) {
        if (node.isGroup()) return node.label;
        if (node.isFile()) return noteTitleOf(node.file);
        String text = node.text == null ? "" : node.text.trim();
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private void pickImage(float x, float y) {
        pendingImageX = x;
        pendingImageY = y;
        imagePicker.launch("image/*");
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) return;
        final float x = pendingImageX;
        final float y = pendingImageY;
        Io.onDisk(() -> {
            String reference = null;
            try {
                java.io.InputStream input = getContentResolver().openInputStream(uri);
                if (input != null) {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    input.close();
                    reference = repo.importAttachment("canvas-image-"
                            + System.currentTimeMillis() + imageExtension(uri), output.toByteArray());
                }
            } catch (Throwable ignored) {}
            final String imported = reference;
            Io.onMain(() -> {
                if (imported == null) { toast(R.string.image_import_failed); return; }
                CanvasDoc.Node node = canvasView.addFileNode(imported, x, y);
                node.width = 280f;
                node.height = 190f;
                canvasView.invalidateNode(node);
            });
        });
    }

    private String imageExtension(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type == null) return ".png";
        if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
        if (type.contains("webp")) return ".webp";
        if (type.contains("gif")) return ".gif";
        return ".png";
    }

    private static boolean isImagePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private void showNotePicker(final float centreX, final float centreY) {
        final List<Note> notes = repo.index().all();
        if (notes.isEmpty()) {
            toast(R.string.canvas_no_notes);
            return;
        }
        Collections.sort(notes, new Comparator<Note>() {
            @Override public int compare(Note a, Note b) {
                return a.getTitle().compareToIgnoreCase(b.getTitle());
            }
        });
        CharSequence[] labels = new CharSequence[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            labels[i] = note.folder().isEmpty()
                    ? note.getTitle() : note.getTitle() + "  ·  " + note.folder();
        }
        dialog()
                .setTitle(R.string.canvas_add_note)
                .setItems(labels, (dialog, which) -> canvasView.addFileNode(
                        notes.get(which).getId(), centreX - 130f, centreY - 48f))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Attaches selected text cards to a vault note while keeping their own canvas wording. */
    private void showNoteLinkPicker() {
        final List<Note> notes = new ArrayList<>(repo.index().all());
        if (notes.isEmpty()) {
            toast(R.string.canvas_no_notes);
            return;
        }
        Collections.sort(notes, new Comparator<Note>() {
            @Override public int compare(Note a, Note b) {
                return a.getTitle().compareToIgnoreCase(b.getTitle());
            }
        });
        CharSequence[] labels = new CharSequence[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            labels[i] = note.folder().isEmpty()
                    ? note.getTitle() : note.getTitle() + "  ·  " + note.folder();
        }
        dialog().setTitle(R.string.canvas_link_note)
                .setItems(labels, (dialog, which) ->
                        canvasView.setNoteLinkForSelection(notes.get(which).getId()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openNote(CanvasDoc.Node node) {
        if (repo.index().get(node.file) == null) {
            toast(R.string.note_not_found);
            return;
        }
        NoteEditorActivity.open(this, node.file);
    }

    private void openLinkedNote(CanvasDoc.Node node) {
        if (node == null || node.note == null || node.note.isEmpty()
                || repo.index().get(node.note) == null) {
            toast(R.string.note_not_found);
            return;
        }
        NoteEditorActivity.open(this, node.note);
    }

    private String noteTitleOf(String notePath) {
        Note note = repo.index().get(notePath);
        return note == null ? NoteFile.titleFromPath(notePath) : note.getTitle();
    }

    private void showCanvasSearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.canvas_search_hint);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad / 2, pad, 0);
        dialog().setTitle(R.string.canvas_search)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.search, (dialog, which) ->
                        showCanvasSearchResults(input.getText().toString()))
                .show();
    }

    private void showCanvasSearchResults(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return;
        List<CanvasDoc.Node> matches = new ArrayList<>();
        for (CanvasDoc.Node node : canvasView.document().nodes()) {
            String haystack = (node.text + "\n" + node.label + "\n" + node.file + "\n"
                    + node.note + "\n" + noteTitleOf(node.file) + "\n"
                    + noteTitleOf(node.note)).toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) matches.add(node);
        }
        if (matches.isEmpty()) {
            toast(R.string.canvas_search_empty);
            return;
        }
        if (matches.size() == 1) {
            canvasView.focusOnNode(matches.get(0));
            return;
        }
        CharSequence[] labels = new CharSequence[matches.size()];
        for (int i = 0; i < matches.size(); i++) labels[i] = nodeLabel(matches.get(i));
        dialog().setTitle(getString(R.string.canvas_search_results, matches.size()))
                .setItems(labels, (dialog, which) -> canvasView.focusOnNode(matches.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String folderOf(String id) {
        int slash = id == null ? -1 : id.lastIndexOf('/');
        return slash < 0 ? "" : id.substring(0, slash);
    }

    private boolean gone() {
        return isFinishing() || isDestroyed();
    }
}
