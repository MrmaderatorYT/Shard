package com.ccs.shard;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.Ui;
import com.ccs.shard.util.RelativeTime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deleted notes, recoverable until the retention window passes.
 *
 * <p>Deleting is the one action in a notes app that must never be a surprise, so
 * nothing is destroyed at the point of deletion: notes move here, they show when
 * they were removed, and permanent deletion is a separate, explicit choice.
 */
public final class TrashActivity extends BaseActivity {

    private RecyclerView list;
    private TrashAdapter adapter;
    private View emptyState;
    private TextView action;
    private View toolbar;
    private View selectionBar;
    private TextView selectionCount;
    private boolean selectionMode;
    private final Set<String> selectedEntries = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_list);

        ((TextView) findViewById(R.id.screenTitle)).setText(R.string.trash_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        list = findViewById(R.id.itemList);
        emptyState = findViewById(R.id.emptyState);
        action = findViewById(R.id.screenAction);
        toolbar = findViewById(R.id.simpleToolbar);
        selectionBar = findViewById(R.id.simpleSelectionBar);
        selectionCount = findViewById(R.id.simpleSelectionCount);
        findViewById(R.id.btnSimpleSelectionClose).setOnClickListener(v -> exitSelection());
        findViewById(R.id.btnSimpleSelectionAll).setOnClickListener(v -> toggleSelectAll());
        findViewById(R.id.btnSimpleSelectionActions).setOnClickListener(this::showBulkActions);
        ((TextView) findViewById(R.id.emptyTitle)).setText(R.string.trash_empty_title);
        ((TextView) findViewById(R.id.emptyBody)).setText(
                getString(R.string.trash_empty_body, prefs.trashRetentionDays()));

        adapter = new TrashAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        action.setText(R.string.trash_empty_action);
        action.setOnClickListener(v -> confirmEmpty());

        reload();
    }

    private void reload() {
        List<VaultRepository.TrashEntry> entries = repo.trash();
        Set<String> available = new LinkedHashSet<>();
        for (VaultRepository.TrashEntry entry : entries) available.add(entryKey(entry));
        selectedEntries.retainAll(available);
        adapter.submit(entries);
        adapter.setSelectedIds(selectedEntries);
        boolean empty = entries.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        action.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (selectionMode && selectedEntries.isEmpty()) exitSelection();
        else updateSelectionChrome();
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            exitSelection();
            return;
        }
        super.onBackPressed();
    }

    private void confirmEmpty() {
        final int count = adapter.getItemCount();
        confirm(R.string.trash_empty_q,
                getString(R.string.trash_empty_body_q, count),
                R.string.trash_delete_forever,
                () -> repo.emptyTrash(this::reload));
    }

    private void showEntryMenu(View anchor, final VaultRepository.TrashEntry entry) {
        AnchoredMenu.vertical(this)
                .title(entry.title())
                .add(1, R.drawable.ic_undo, getString(R.string.trash_restore))
                .add(new AnchoredMenu.Item(2, R.drawable.ic_delete,
                        getString(R.string.trash_delete_forever)).destructive())
                .divider()
                .add(3, R.drawable.ic_check, getString(R.string.note_select))
                .onItem(id -> {
                    if (id == 1) {
                        repo.restoreFromTrash(entry, () -> {
                            toast(R.string.restored);
                            reload();
                        });
                    } else if (id == 2) {
                        //noinspection ResultOfMethodCallIgnored
                        entry.file.delete();
                        reload();
                    } else if (id == 3) {
                        enterSelection(entry);
                    }
                })
                .showAt(anchor);
    }

    private String entryKey(VaultRepository.TrashEntry entry) {
        return entry == null || entry.file == null ? "" : entry.file.getAbsolutePath();
    }

    private void enterSelection(VaultRepository.TrashEntry entry) {
        if (entry == null) return;
        selectionMode = true;
        selectedEntries.clear();
        selectedEntries.add(entryKey(entry));
        adapter.setSelectionMode(true);
        adapter.setSelectedIds(selectedEntries);
        updateSelectionChrome();
    }

    private void exitSelection() {
        selectionMode = false;
        selectedEntries.clear();
        adapter.setSelectionMode(false);
        updateSelectionChrome();
    }

    private void updateSelectionChrome() {
        if (toolbar == null || selectionBar == null) return;
        toolbar.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (selectionMode) {
            selectionCount.setText(getString(R.string.selected_notes_count,
                    selectedEntries.size()));
        }
    }

    private void toggleEntrySelection(VaultRepository.TrashEntry entry) {
        if (entry == null) return;
        String key = entryKey(entry);
        if (!selectedEntries.add(key)) selectedEntries.remove(key);
        adapter.setSelected(key, selectedEntries.contains(key));
        updateSelectionChrome();
        if (selectedEntries.isEmpty()) exitSelection();
    }

    private void toggleSelectAll() {
        if (!selectionMode) return;
        List<String> visible = adapter.entryKeys();
        boolean allSelected = !visible.isEmpty() && selectedEntries.containsAll(visible);
        if (allSelected) selectedEntries.clear();
        else selectedEntries.addAll(visible);
        adapter.setSelectedIds(selectedEntries);
        updateSelectionChrome();
        if (selectedEntries.isEmpty()) exitSelection();
    }

    private List<VaultRepository.TrashEntry> selectedTrashEntries() {
        List<VaultRepository.TrashEntry> selected = new ArrayList<>();
        for (VaultRepository.TrashEntry entry : repo.trash()) {
            if (selectedEntries.contains(entryKey(entry))) selected.add(entry);
        }
        return selected;
    }

    private void showBulkActions(View anchor) {
        if (selectedEntries.isEmpty()) return;
        AnchoredMenu.vertical(this)
                .title(getString(R.string.selected_notes_count, selectedEntries.size()))
                .add(1, R.drawable.ic_undo, getString(R.string.trash_restore))
                .add(new AnchoredMenu.Item(2, R.drawable.ic_delete,
                        getString(R.string.trash_delete_forever)).destructive())
                .onItem(id -> {
                    if (id == 1) bulkRestore();
                    else if (id == 2) confirmBulkDelete();
                })
                .showAt(anchor);
    }

    private void bulkRestore() {
        final List<VaultRepository.TrashEntry> entries = selectedTrashEntries();
        exitSelection();
        repo.bulkRestoreFromTrash(entries, new com.ccs.shard.core.Io.Ok<Integer>() {
            @Override public void onReady(Integer count) {
                toast(getString(R.string.bulk_restored, count == null ? 0 : count));
                reload();
            }

            @Override public void onError(Throwable t) {
                toast(R.string.bulk_operation_failed);
                reload();
            }
        });
    }

    private void confirmBulkDelete() {
        final List<VaultRepository.TrashEntry> entries = selectedTrashEntries();
        confirm(R.string.bulk_trash_delete_title,
                getString(R.string.bulk_trash_delete_body, entries.size()),
                R.string.trash_delete_forever,
                () -> {
                    exitSelection();
                    repo.deleteTrashEntries(entries, new com.ccs.shard.core.Io.Ok<Integer>() {
                        @Override public void onReady(Integer count) {
                            toast(getString(R.string.bulk_trash_deleted,
                                    count == null ? 0 : count));
                            reload();
                        }

                        @Override public void onError(Throwable t) {
                            toast(R.string.bulk_operation_failed);
                            reload();
                        }
                    });
                });
    }

    private final class TrashAdapter extends RecyclerView.Adapter<TrashAdapter.Holder> {
        private List<VaultRepository.TrashEntry> entries = new ArrayList<>();
        private boolean selectionMode;
        private final Set<String> selectedIds = new LinkedHashSet<>();

        void submit(List<VaultRepository.TrashEntry> newEntries) {
            entries = newEntries == null ? new ArrayList<>() : newEntries;
            notifyDataSetChanged();
        }

        void setSelectionMode(boolean enabled) {
            selectionMode = enabled;
            if (!enabled) selectedIds.clear();
            notifyDataSetChanged();
        }

        void setSelected(String key, boolean selected) {
            if (selected) selectedIds.add(key);
            else selectedIds.remove(key);
            notifyDataSetChanged();
        }

        void setSelectedIds(Set<String> ids) {
            selectedIds.clear();
            if (ids != null) selectedIds.addAll(ids);
            notifyDataSetChanged();
        }

        List<String> entryKeys() {
            List<String> keys = new ArrayList<>(entries.size());
            for (VaultRepository.TrashEntry entry : entries) keys.add(entryKey(entry));
            return keys;
        }

        @Override
        public int getItemCount() { return entries.size(); }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_note, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(entries.get(position));
        }

        final class Holder extends RecyclerView.ViewHolder {
            private final TextView title;
            private final TextView excerpt;
            private final TextView meta;
            private final View stripe;
            private final ImageView selected;

            Holder(View view) {
                super(view);
                title = view.findViewById(R.id.noteTitle);
                excerpt = view.findViewById(R.id.noteExcerpt);
                meta = view.findViewById(R.id.noteMeta);
                stripe = view.findViewById(R.id.colorStripe);
                selected = view.findViewById(R.id.noteSelected);
                view.findViewById(R.id.noteEmoji).setVisibility(View.GONE);
                ((ImageView) view.findViewById(R.id.notePinned)).setVisibility(View.GONE);
                ((ImageView) view.findViewById(R.id.noteStarred)).setVisibility(View.GONE);
                view.findViewById(R.id.noteDragHandle).setVisibility(View.GONE);
            }

            void bind(final VaultRepository.TrashEntry entry) {
                title.setText(entry.title());
                excerpt.setText(entry.originalId);
                meta.setText(getString(R.string.trash_deleted_at,
                        RelativeTime.format(TrashActivity.this, entry.deletedAt)));
                stripe.setBackground(Ui.roundRect(
                        Ui.themeColor(TrashActivity.this,
                                com.google.android.material.R.attr.colorOutline),
                        Ui.dp(TrashActivity.this, 2)));
                selected.setVisibility(selectionMode && selectedIds.contains(entryKey(entry))
                        ? View.VISIBLE : View.GONE);
                itemView.setOnClickListener(v -> {
                    if (selectionMode) toggleEntrySelection(entry);
                    else showEntryMenu(v, entry);
                });
                itemView.setOnLongClickListener(v -> {
                    Ui.hapticLongPress(v);
                    if (selectionMode) toggleEntrySelection(entry);
                    else showEntryMenu(v, entry);
                    return true;
                });
            }
        }
    }
}
