package com.ccs.shard.ui;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Build;
import android.view.LayoutInflater;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.shard.R;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.util.NoteSorter;
import com.ccs.shard.util.RelativeTime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders the vault browser: section headers, folders and notes in one list.
 *
 * <p>Rows show only indexed metadata, so scrolling a large vault never touches
 * the disk. Long-press opens an {@link AnchoredMenu} beside the row instead of a
 * bottom sheet, which keeps the row you are acting on visible.
 */
public final class VaultListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onNoteClicked(Note note);
        void onNoteSelectionToggle(Note note);
        void onNoteMenu(View anchor, Note note, float x, float y);
        void onFolderClicked(String path, String name);
        void onFolderMenu(View anchor, String path, String name);
        void onNoteDropped(String noteId, String folderPath);
    }

    private final Context context;
    private final Listener listener;
    private final LayoutInflater inflater;
    private final int[] hues;

    private List<VaultItem> items = new ArrayList<>();
    private String highlightQuery = "";
    private boolean selectionMode;
    private final Set<String> selectedIds = new HashSet<>();

    public VaultListAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.inflater = LayoutInflater.from(context);
        this.hues = new int[]{
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_1),
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_2),
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_3),
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_4),
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_5),
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_6),
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_7),
                androidx.core.content.ContextCompat.getColor(context, R.color.hue_8),
        };
        setHasStableIds(true);
    }

    public void submit(List<VaultItem> newItems) {
        final List<VaultItem> replacement = newItems == null
                ? new ArrayList<>() : new ArrayList<>(newItems);
        final List<VaultItem> previous = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return replacement.size(); }

            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).id() == replacement.get(newPosition).id();
            }

            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return sameContent(previous.get(oldPosition), replacement.get(newPosition));
            }
        });
        this.items = replacement;
        diff.dispatchUpdatesTo(this);
    }

    private static boolean sameContent(VaultItem a, VaultItem b) {
        if (a.type != b.type) return false;
        if (!String.valueOf(a.label).contentEquals(String.valueOf(b.label))) return false;
        if (!String.valueOf(a.snippet).contentEquals(String.valueOf(b.snippet))) return false;
        if (a.type == VaultItem.TYPE_FOLDER) {
            return a.folder != null && b.folder != null
                    && a.folder.path.equals(b.folder.path)
                    && a.folder.name.equals(b.folder.name)
                    && a.folder.noteCount == b.folder.noteCount;
        }
        if (a.type == VaultItem.TYPE_NOTE) {
            if (a.note == null || b.note == null) return a.note == b.note;
            return a.note.getTitle().equals(b.note.getTitle())
                    && a.note.getModifiedMillis() == b.note.getModifiedMillis()
                    && a.note.getSizeBytes() == b.note.getSizeBytes()
                    && a.note.isPinned() == b.note.isPinned()
                    && a.note.isBookmarked() == b.note.isBookmarked()
                    && a.note.isArchived() == b.note.isArchived();
        }
        return true;
    }

    public void setHighlightQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.equals(highlightQuery)) return;
        highlightQuery = value;
        notifyDataSetChanged();
    }

    public VaultItem itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    public int itemCount() { return items.size(); }

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selectedIds.clear();
        notifyDataSetChanged();
    }

    public void setSelected(String noteId, boolean selected) {
        if (noteId == null) return;
        if (selected) selectedIds.add(noteId);
        else selectedIds.remove(noteId);
        notifyDataSetChanged();
    }

    public void setSelectedIds(Collection<String> ids) {
        selectedIds.clear();
        if (ids != null) selectedIds.addAll(ids);
        notifyDataSetChanged();
    }

    public List<String> noteIds() {
        List<String> ids = new ArrayList<>();
        for (VaultItem item : items) {
            if (item.type == VaultItem.TYPE_NOTE && item.note != null) {
                ids.add(item.note.getId());
            }
        }
        return ids;
    }

    @Override
    public int getItemCount() { return items.size(); }

    @Override
    public long getItemId(int position) { return items.get(position).id(); }

    @Override
    public int getItemViewType(int position) { return items.get(position).type; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        switch (viewType) {
            case VaultItem.TYPE_HEADER:
                return new HeaderHolder(
                        inflater.inflate(R.layout.item_section_header, parent, false));
            case VaultItem.TYPE_FOLDER:
                return new FolderHolder(
                        inflater.inflate(R.layout.item_folder, parent, false));
            default:
                return new NoteHolder(inflater.inflate(R.layout.item_note, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        VaultItem item = items.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).bind(item);
        } else if (holder instanceof FolderHolder) {
            ((FolderHolder) holder).bind(item);
        } else if (holder instanceof NoteHolder) {
            ((NoteHolder) holder).bind(item);
        }
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        private final TextView label;

        HeaderHolder(View view) {
            super(view);
            label = view.findViewById(R.id.sectionLabel);
        }

        void bind(VaultItem item) {
            label.setText(item.label);
        }
    }

    final class FolderHolder extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView count;

        FolderHolder(View view) {
            super(view);
            name = view.findViewById(R.id.folderName);
            count = view.findViewById(R.id.folderCount);
        }

        void bind(final VaultItem item) {
            name.setText(item.folder.name);
            count.setText(context.getResources().getQuantityString(
                    R.plurals.notes_count, item.folder.noteCount, item.folder.noteCount));
            itemView.setOnClickListener(v -> {
                if (!selectionMode) listener.onFolderClicked(item.folder.path, item.folder.name);
            });
            itemView.setOnLongClickListener(v -> {
                if (selectionMode) return true;
                Ui.hapticLongPress(v);
                listener.onFolderMenu(v, item.folder.path, item.folder.name);
                return true;
            });
            itemView.setOnDragListener((v, event) -> {
                ClipDescription description = event.getClipDescription();
                boolean noteDrag = description != null
                        && "shard-note".contentEquals(description.getLabel());
                if (!noteDrag) return false;
                switch (event.getAction()) {
                    case android.view.DragEvent.ACTION_DRAG_ENTERED:
                        v.animate().scaleX(1.02f).scaleY(1.02f).alpha(0.72f)
                                .setDuration(100L).start();
                        return true;
                    case android.view.DragEvent.ACTION_DRAG_EXITED:
                    case android.view.DragEvent.ACTION_DRAG_ENDED:
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(120L).start();
                        return true;
                    case android.view.DragEvent.ACTION_DROP:
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(120L).start();
                        ClipData data = event.getClipData();
                        if (data != null && data.getItemCount() > 0) {
                            listener.onNoteDropped(data.getItemAt(0)
                                    .coerceToText(context).toString(), item.folder.path);
                        }
                        return true;
                    default:
                        return true;
                }
            });
        }
    }

    final class NoteHolder extends RecyclerView.ViewHolder {
        private final View stripe;
        private final TextView emoji;
        private final TextView title;
        private final TextView excerpt;
        private final TextView meta;
        private final ImageView pinned;
        private final ImageView starred;
        private final ImageView selected;
        private final ImageView dragHandle;
        private float lastTouchX;
        private float lastTouchY;

        NoteHolder(View view) {
            super(view);
            stripe = view.findViewById(R.id.colorStripe);
            emoji = view.findViewById(R.id.noteEmoji);
            title = view.findViewById(R.id.noteTitle);
            excerpt = view.findViewById(R.id.noteExcerpt);
            meta = view.findViewById(R.id.noteMeta);
            pinned = view.findViewById(R.id.notePinned);
            starred = view.findViewById(R.id.noteStarred);
            selected = view.findViewById(R.id.noteSelected);
            dragHandle = view.findViewById(R.id.noteDragHandle);
            // Remember where the finger was so the menu can open at that point.
            view.setOnTouchListener((v, event) -> {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return false;
            });
        }

        void bind(final VaultItem item) {
            final Note note = item.note;
            title.setText(highlight(note.getTitle()));

            CharSequence body = item.snippet != null ? item.snippet : note.getExcerpt();
            excerpt.setText(highlight(body));
            excerpt.setVisibility(body.length() == 0 ? View.GONE : View.VISIBLE);

            stripe.setBackground(Ui.roundRect(
                    NoteSorter.hueFor(note, hues), Ui.dp(context, 2)));

            String icon = note.getEmoji();
            emoji.setText(icon);
            emoji.setVisibility(icon.isEmpty() ? View.GONE : View.VISIBLE);

            StringBuilder line = new StringBuilder();
            if (NoteFile.isTexFile(note.getId())) line.append("TeX  ·  ");
            line.append(RelativeTime.format(context, note.getModifiedMillis()));
            if (note.getWordCount() > 0) {
                line.append("  ·  ").append(
                        context.getString(R.string.words_count, note.getWordCount()));
            }
            for (String tag : note.getTags()) {
                line.append("  ·  #").append(tag);
                if (line.length() > 90) break;
            }
            meta.setText(line);

            pinned.setVisibility(note.isPinned() ? View.VISIBLE : View.GONE);
            starred.setVisibility(note.isBookmarked() ? View.VISIBLE : View.GONE);
            selected.setVisibility(selectionMode && selectedIds.contains(note.getId())
                    ? View.VISIBLE : View.GONE);
            selected.setContentDescription(context.getString(R.string.note_selected));
            itemView.setAlpha(note.isArchived() ? 0.55f : 1f);

            itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    listener.onNoteSelectionToggle(note);
                } else {
                    listener.onNoteClicked(note);
                }
            });
            itemView.setOnLongClickListener(v -> {
                Ui.hapticLongPress(v);
                if (selectionMode) listener.onNoteSelectionToggle(note);
                else listener.onNoteMenu(v, note, lastTouchX, lastTouchY);
                return true;
            });
            dragHandle.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            dragHandle.setOnClickListener(v ->
                    listener.onNoteMenu(itemView, note, dragHandle.getX(), dragHandle.getY()));
            dragHandle.setOnLongClickListener(v -> {
                Ui.hapticLongPress(v);
                ClipData data = ClipData.newPlainText("shard-note", note.getId());
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(itemView);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    itemView.startDragAndDrop(data, shadow, null, 0);
                } else {
                    //noinspection deprecation
                    itemView.startDrag(data, shadow, null, 0);
                }
                return true;
            });
        }
    }

    private CharSequence highlight(CharSequence source) {
        if (source == null || source.length() == 0 || highlightQuery.isEmpty()) return source;
        String text = source.toString();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String needle = highlightQuery.toLowerCase(java.util.Locale.ROOT);
        int at = lower.indexOf(needle);
        if (at < 0) return source;
        SpannableString highlighted = new SpannableString(text);
        int color = Ui.withAlpha(Ui.themeColor(context,
                com.google.android.material.R.attr.colorPrimary), 0.22f);
        while (at >= 0) {
            int end = at + needle.length();
            highlighted.setSpan(new BackgroundColorSpan(color), at, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            highlighted.setSpan(new StyleSpan(Typeface.BOLD), at, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            at = lower.indexOf(needle, end);
        }
        return highlighted;
    }
}
