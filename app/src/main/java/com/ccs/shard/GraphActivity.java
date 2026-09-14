package com.ccs.shard;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.graph.GraphModel;
import com.ccs.shard.graph.GraphView;
import com.ccs.shard.ui.AnchoredMenu;
import com.google.android.material.button.MaterialButton;

/**
 * The graph screen.
 *
 * <p>Two modes: the whole vault, and the neighbourhood of one note. The local
 * view is the one that stays useful as a vault grows — a global graph of two
 * thousand notes is a picture, while two hops around the note you are reading is
 * a navigation tool.
 *
 * <p>The model is built on a background thread because it walks every note's
 * links; the layout that follows runs on its own worker.
 */
public final class GraphActivity extends BaseActivity implements GraphView.Listener {

    private static final String EXTRA_FOCUS_NOTE = "focus_note";

    private GraphView graphView;
    private TextView title;
    private TextView stats;
    private View selectionCard;
    private TextView selectionTitle;
    private TextView selectionMeta;
    private MaterialButton openButton;
    private MaterialButton localButton;
    private View emptyState;

    private String focusNoteId;
    private int localDepth = 2;
    private String selectedKey;
    private int selectedKind;

    public static void start(Context context) {
        context.startActivity(new Intent(context, GraphActivity.class));
    }

    public static void startFocused(Context context, String noteId) {
        Intent intent = new Intent(context, GraphActivity.class);
        intent.putExtra(EXTRA_FOCUS_NOTE, noteId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);

        graphView = findViewById(R.id.graphView);
        title = findViewById(R.id.graphTitle);
        stats = findViewById(R.id.graphStats);
        selectionCard = findViewById(R.id.selectionCard);
        selectionTitle = findViewById(R.id.selectionTitle);
        selectionMeta = findViewById(R.id.selectionMeta);
        openButton = findViewById(R.id.btnOpenNote);
        localButton = findViewById(R.id.btnLocalGraph);
        emptyState = findViewById(R.id.graphEmpty);

        graphView.setListener(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnFit).setOnClickListener(v -> graphView.fitToScreen());
        findViewById(R.id.btnGraphOptions).setOnClickListener(this::showOptions);
        openButton.setOnClickListener(v -> openSelected());
        localButton.setOnClickListener(v -> focusSelected());

        focusNoteId = getIntent().getStringExtra(EXTRA_FOCUS_NOTE);
        repo.open(this::rebuild);
    }

    @Override
    protected void onDestroy() {
        graphView.stopLayout();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- building

    private void rebuild() {
        final boolean local = focusNoteId != null;
        final boolean showTags = prefs.graphShowTags();
        final boolean showOrphans = prefs.graphShowOrphans();
        final String focus = focusNoteId;
        final int depth = localDepth;

        title.setText(local ? noteTitleOf(focus) : getString(R.string.nav_graph));
        stats.setText(R.string.graph_building);
        selectionCard.setVisibility(View.GONE);

        // Building walks every note's link list, so keep it off the main thread.
        Io.load(new Io.Task<GraphModel>() {
            @Override public GraphModel run() {
                return local
                        ? GraphModel.buildLocal(repo.index(), focus, depth, showTags)
                        : GraphModel.build(repo.index(), showTags, showOrphans, true);
            }
        }, new Io.Ok<GraphModel>() {
            @Override public void onReady(GraphModel model) {
                graphView.setShowLabels(prefs.graphShowLabels());
                // A big global graph is a hairball at any zoom; start it filtered so
                // the first thing the user sees is the connected core.
                graphView.setMinDegree(!local && model.nodeCount > 400 ? 2 : 0);
                graphView.setModel(model);
                boolean empty = model.nodeCount == 0;
                emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                updateStats();
                if (local) {
                    int index = model.indexOf(focus);
                    if (index >= 0) {
                        graphView.select(index);
                        graphView.post(() -> graphView.centreOn(index));
                    }
                }
            }
        });
    }

    private void updateStats() {
        GraphModel model = graphView.model();
        int visibleNodes = 0;
        for (int i = 0; i < model.nodeCount; i++) {
            if (model.degree[i] >= graphView.minDegree()) visibleNodes++;
        }
        String text = getString(R.string.graph_stats, visibleNodes, model.edgeCount);
        if (graphView.minDegree() > 0) {
            text = text + "  ·  " + getString(R.string.graph_min_links,
                    graphView.minDegree());
        }
        stats.setText(text);
    }

    private String noteTitleOf(String noteId) {
        Note note = repo.index().get(noteId);
        return note == null ? getString(R.string.nav_graph) : note.getTitle();
    }

    // ---------------------------------------------------------------- selection

    @Override
    public void onNodeSelected(int index, String key, String label, int kind) {
        selectedKey = key;
        selectedKind = kind;
        selectionCard.setVisibility(View.VISIBLE);
        selectionTitle.setText(label);

        switch (kind) {
            case GraphModel.KIND_TAG: {
                int count = repo.index().withTag(key.substring(1)).size();
                selectionMeta.setText(getResources().getQuantityString(
                        R.plurals.notes_count, count, count));
                openButton.setText(R.string.graph_open_tag);
                localButton.setVisibility(View.GONE);
                break;
            }
            case GraphModel.KIND_MISSING:
                selectionMeta.setText(R.string.tap_to_create);
                openButton.setText(R.string.graph_create_note);
                localButton.setVisibility(View.GONE);
                break;
            default: {
                Note note = repo.index().get(key);
                if (note == null) {
                    selectionMeta.setText("");
                } else {
                    int backlinks = repo.index().backlinkCount(note);
                    int outgoing = note.getOutgoingLinks().size();
                    selectionMeta.setText(getString(R.string.graph_node_meta,
                            outgoing, backlinks));
                }
                openButton.setText(R.string.note_open);
                localButton.setVisibility(View.VISIBLE);
                break;
            }
        }
    }

    @Override
    public void onNodeOpened(int index, String key, int kind) {
        selectedKey = key;
        selectedKind = kind;
        openSelected();
    }

    @Override
    public void onSelectionCleared() {
        selectedKey = null;
        selectionCard.setVisibility(View.GONE);
    }

    private void openSelected() {
        if (selectedKey == null) return;
        switch (selectedKind) {
            case GraphModel.KIND_TAG: {
                Intent intent = new Intent(this, HomeActivity.class);
                intent.putExtra(HomeActivity.EXTRA_FILTER_TAG, selectedKey.substring(1));
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                break;
            }
            case GraphModel.KIND_MISSING: {
                if (repo.vault().isSharedStorage() && !repo.vault().hasFullFileAccess()) {
                    startActivity(new Intent(this, HomeActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    break;
                }
                String titleText = selectedKey.startsWith("?")
                        ? selectedKey.substring(1) : selectedKey;
                Note created = repo.createNote(titleText, "", "");
                NoteEditorActivity.open(this, created.getId());
                break;
            }
            default:
                NoteEditorActivity.open(this, selectedKey);
                break;
        }
    }

    private void focusSelected() {
        if (selectedKey == null || selectedKind != GraphModel.KIND_NOTE) return;
        focusNoteId = selectedKey;
        rebuild();
    }

    // ---------------------------------------------------------------- options

    private void showOptions(View anchor) {
        boolean local = focusNoteId != null;
        AnchoredMenu menu = AnchoredMenu.vertical(this)
                .title(getString(R.string.graph_options));
        if (local) {
            menu.add(1, R.drawable.ic_graph, getString(R.string.graph_show_whole_vault));
            menu.add(new AnchoredMenu.Item(2, R.drawable.ic_local_graph,
                    getString(R.string.graph_depth, localDepth)));
            menu.divider();
        }
        menu.add(new AnchoredMenu.Item(3, R.drawable.ic_tag,
                        getString(R.string.graph_show_tags)).checked(prefs.graphShowTags()))
                .add(new AnchoredMenu.Item(4, R.drawable.ic_notes,
                        getString(R.string.graph_show_orphans))
                        .checked(prefs.graphShowOrphans()).enabled(!local))
                .add(new AnchoredMenu.Item(5, R.drawable.ic_block_text,
                        getString(R.string.graph_show_labels))
                        .checked(prefs.graphShowLabels()))
                .add(new AnchoredMenu.Item(7, R.drawable.ic_filter,
                        getString(R.string.graph_min_links, graphView.minDegree())))
                .divider()
                .add(6, R.drawable.ic_select_all, getString(R.string.graph_fit))
                .onItem(id -> {
                    switch (id) {
                        case 1:
                            focusNoteId = null;
                            rebuild();
                            break;
                        case 2:
                            localDepth = localDepth >= 3 ? 1 : localDepth + 1;
                            rebuild();
                            break;
                        case 3:
                            prefs.setGraphShowTags(!prefs.graphShowTags());
                            rebuild();
                            break;
                        case 4:
                            prefs.setGraphShowOrphans(!prefs.graphShowOrphans());
                            rebuild();
                            break;
                        case 5:
                            prefs.setGraphShowLabels(!prefs.graphShowLabels());
                            graphView.setShowLabels(prefs.graphShowLabels());
                            break;
                        case 7: {
                            // Cycles through useful thresholds rather than opening a
                            // slider: on a large vault "2 or more links" is usually
                            // the setting that makes the graph readable.
                            int[] steps = {0, 2, 4, 8};
                            int current = graphView.minDegree();
                            int next = steps[0];
                            for (int i = 0; i < steps.length; i++) {
                                if (steps[i] == current) {
                                    next = steps[(i + 1) % steps.length];
                                    break;
                                }
                            }
                            graphView.setMinDegree(next);
                            graphView.fitToScreen();
                            updateStats();
                            break;
                        }
                        case 6:
                            graphView.fitToScreen();
                            break;
                        default:
                            break;
                    }
                })
                .showAt(anchor);
    }
}
