package com.ccs.shard;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.VersionManager;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.Ui;
import com.ccs.shard.util.RelativeTime;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshots of a note over time, with restore and a line diff against the
 * current text.
 *
 * <p>Restoring takes a snapshot of the current text first, so the action is
 * itself undoable — which is what makes it safe to offer at all.
 */
public final class HistoryActivity extends BaseActivity {

    private static final String EXTRA_NOTE_ID = "note_id";

    private String noteId;
    private VersionManager versions;
    private HistoryAdapter adapter;
    private View emptyState;

    public static void start(Context context, String noteId) {
        Intent intent = new Intent(context, HistoryActivity.class);
        intent.putExtra(EXTRA_NOTE_ID, noteId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_list);

        noteId = getIntent().getStringExtra(EXTRA_NOTE_ID);
        versions = repo.versions();

        Note note = noteId == null ? null : repo.index().get(noteId);
        ((TextView) findViewById(R.id.screenTitle)).setText(note == null
                ? getString(R.string.history_title) : note.getTitle());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.emptyTitle)).setText(R.string.history_empty_title);
        ((TextView) findViewById(R.id.emptyBody)).setText(R.string.history_empty_body);
        ((android.widget.ImageView) findViewById(R.id.emptyIcon))
                .setImageResource(R.drawable.ic_history);

        emptyState = findViewById(R.id.emptyState);
        RecyclerView list = findViewById(R.id.itemList);
        adapter = new HistoryAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        reload();
    }

    private void reload() {
        Io.load(new Io.Task<List<VersionManager.Version>>() {
            @Override public List<VersionManager.Version> run() {
                return versions.list(noteId);
            }
        }, new Io.Ok<List<VersionManager.Version>>() {
            @Override public void onReady(List<VersionManager.Version> value) {
                adapter.submit(value);
                emptyState.setVisibility(value.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void showVersionMenu(View anchor, final VersionManager.Version version) {
        AnchoredMenu.vertical(this)
                .title(version.formattedDate())
                .add(1, R.drawable.ic_block_text, getString(R.string.history_preview))
                .add(2, R.drawable.ic_split, getString(R.string.history_diff))
                .add(3, R.drawable.ic_undo, getString(R.string.history_restore))
                .divider()
                .add(new AnchoredMenu.Item(4, R.drawable.ic_delete,
                        getString(R.string.history_delete)).destructive())
                .onItem(id -> {
                    switch (id) {
                        case 1: showPreview(version); break;
                        case 2: showDiff(version); break;
                        case 3: confirmRestore(version); break;
                        case 4:
                            versions.delete(version);
                            reload();
                            break;
                        default: break;
                    }
                })
                .showAt(anchor);
    }

    private void showPreview(final VersionManager.Version version) {
        Io.load(new Io.Task<String>() {
            @Override public String run() { return versions.contentOf(version); }
        }, new Io.Ok<String>() {
            @Override public void onReady(String content) {
                dialog()
                        .setTitle(version.formattedDate())
                        .setMessage(content.length() > 4000
                                ? content.substring(0, 4000) + "…" : content)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private void showDiff(final VersionManager.Version version) {
        Io.load(new Io.Task<CharSequence>() {
            @Override public CharSequence run() throws Exception {
                String old = versions.contentOf(version);
                Note current = repo.loadNoteSync(noteId);
                List<String> lines = VersionManager.diff(old,
                        current.getContent() == null ? "" : current.getContent());
                android.text.SpannableStringBuilder out =
                        new android.text.SpannableStringBuilder();
                int shown = 0;
                for (String line : lines) {
                    if (line.startsWith("  ")) continue;
                    if (shown++ > 200) {
                        out.append("…");
                        break;
                    }
                    int start = out.length();
                    out.append(line).append('\n');
                    int color = line.startsWith("+") ? 0xFF2F9E68 : 0xFFD64545;
                    out.setSpan(new android.text.style.ForegroundColorSpan(color),
                            start, out.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (out.length() == 0) out.append(getString(R.string.history_current));
                return out;
            }
        }, new Io.Ok<CharSequence>() {
            @Override public void onReady(CharSequence value) {
                dialog()
                        .setTitle(R.string.history_diff)
                        .setMessage(value)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private void confirmRestore(final VersionManager.Version version) {
        confirm(R.string.history_restore_q,
                getString(R.string.history_restore_body),
                R.string.history_restore, () -> restore(version));
    }

    private void restore(final VersionManager.Version version) {
        Io.load(new Io.Task<Boolean>() {
            @Override public Boolean run() throws Exception {
                Note current = repo.loadNoteSync(noteId);
                // Snapshot the current text first, so restoring is reversible.
                versions.snapshot(noteId, current.getTitle(),
                        current.getContent() == null ? "" : current.getContent(), true);
                String restored = versions.contentOf(version);
                current.setContent(restored);
                com.ccs.shard.core.NoteFile.save(repo.fileOf(noteId), current);
                repo.index().put(current);
                return Boolean.TRUE;
            }
        }, new Io.Ok<Boolean>() {
            @Override public void onReady(Boolean value) {
                repo.refresh();
                toast(R.string.history_restored);
                reload();
            }
        });
    }

    private final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {
        private List<VersionManager.Version> items = new ArrayList<>();

        void submit(List<VersionManager.Version> newItems) {
            items = newItems;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_note, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(items.get(position), position == 0);
        }

        final class Holder extends RecyclerView.ViewHolder {
            private final TextView title;
            private final TextView excerpt;
            private final TextView meta;
            private final View stripe;

            Holder(View view) {
                super(view);
                title = view.findViewById(R.id.noteTitle);
                excerpt = view.findViewById(R.id.noteExcerpt);
                meta = view.findViewById(R.id.noteMeta);
                stripe = view.findViewById(R.id.colorStripe);
                view.findViewById(R.id.noteEmoji).setVisibility(View.GONE);
                view.findViewById(R.id.notePinned).setVisibility(View.GONE);
                view.findViewById(R.id.noteStarred).setVisibility(View.GONE);
            }

            void bind(final VersionManager.Version version, boolean newest) {
                title.setText(version.formattedDate());
                excerpt.setText(RelativeTime.format(HistoryActivity.this, version.timestamp));
                meta.setText(formatSize(version.sizeBytes()));
                stripe.setBackground(Ui.roundRect(Ui.themeColor(HistoryActivity.this,
                        newest ? com.google.android.material.R.attr.colorPrimary
                                : com.google.android.material.R.attr.colorOutline),
                        Ui.dp(HistoryActivity.this, 2)));
                itemView.setOnClickListener(v -> showVersionMenu(v, version));
                itemView.setOnLongClickListener(v -> {
                    Ui.hapticLongPress(v);
                    showVersionMenu(v, version);
                    return true;
                });
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.getDefault(),
                "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.getDefault(),
                "%.1f MB", bytes / (1024.0 * 1024));
    }
}
