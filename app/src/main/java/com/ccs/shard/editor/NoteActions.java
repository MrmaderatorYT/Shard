package com.ccs.shard.editor;

import android.view.View;

import com.ccs.shard.GraphActivity;
import com.ccs.shard.HistoryActivity;
import com.ccs.shard.PropertiesActivity;
import com.ccs.shard.R;
import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.util.RelativeTime;

import java.util.ArrayList;
import java.util.List;

/**
 * Actions that apply to the note as a whole: the overflow menu, moving it,
 * pinning and starring, the info sheet, the icon picker and deletion.
 *
 * <p>Separate from {@link BlockActions} because the two operate at different
 * scopes — one edits a block inside a document, this one changes the document's
 * identity and place in the vault.
 */
public final class NoteActions {

    private static final int ID_EXPORT = 1;
    private static final int ID_HISTORY = 2;
    private static final int ID_GRAPH = 3;
    private static final int ID_MOVE = 4;
    private static final int ID_DUPLICATE = 5;
    private static final int ID_PIN = 6;
    private static final int ID_STAR = 7;
    private static final int ID_ARCHIVE = 8;
    private static final int ID_INFO = 9;
    private static final int ID_DELETE = 10;
    private static final int ID_PROPERTIES = 11;
    private static final int ID_RAW_MARKDOWN = 12;
    private static final int ID_FIND_REPLACE = 13;
    private static final int ID_OPENED_NOTES = 14;
    private static final int ID_HEADINGS = 15;
    private static final int ID_OPEN_ALONGSIDE = 16;

    /** Icons offered by the note icon picker. */
    private static final String[] ICONS = {
            "📝", "📌", "💡", "📚", "🗂", "✅", "🎯", "🔥", "⭐", "🧠",
            "🧪", "🛠", "📅", "💼", "🏠", "🎨", "🎵", "🌱", "🚀", "❤️",
    };

    /** What this class needs from the editor screen. */
    public interface Host {
        Note note();
        /** Number of blocks, for the info sheet. */
        int blockCount();
        /** Flushes pending edits before an action that moves or copies the file. */
        void commit();
        /** Re-renders the header after the note's identity changed. */
        void refreshChrome();
        void openNote(String noteId);
        /**
         * Called before the note is trashed. The editor must drop any pending
         * autosave here — a save that lands after the delete would recreate the
         * file that was just removed.
         */
        void cancelPendingSave();
        /** The note went to the trash; the screen should close. */
        void onNoteDeleted();
        void showExportMenu();
        /** Properties may change metadata, so the editor reloads on resume. */
        void onPropertiesOpened();
        /** Toggles the plain-text Markdown editor for troubleshooting. */
        void toggleRawMarkdown();
        void showFindReplace();
        void showOpenedNotes();
        void showHeadingNavigator();
        void showNoteAlongside();
    }

    private final BaseActivity activity;
    private final VaultRepository repo;
    private final Prefs prefs;
    private final Host host;

    public NoteActions(BaseActivity activity, VaultRepository repo, Prefs prefs, Host host) {
        this.activity = activity;
        this.repo = repo;
        this.prefs = prefs;
        this.host = host;
    }

    // ---------------------------------------------------------------- menu

    public void showNoteMenu(View anchor) {
        final Note note = host.note();
        if (note == null) return;
        AnchoredMenu.vertical(activity)
                .title(note.getTitle())
                .add(ID_EXPORT, R.drawable.ic_export,
                        activity.getString(R.string.export_note))
                .add(ID_HISTORY, R.drawable.ic_history,
                        activity.getString(R.string.note_history))
                .add(ID_GRAPH, R.drawable.ic_local_graph,
                        activity.getString(R.string.note_open_graph))
                .divider()
                .add(ID_OPENED_NOTES, R.drawable.ic_recent,
                        activity.getString(R.string.opened_notes))
                .add(ID_HEADINGS, R.drawable.ic_format_heading,
                        activity.getString(R.string.go_to_heading))
                .add(ID_OPEN_ALONGSIDE, R.drawable.ic_split,
                        activity.getString(R.string.open_note_alongside))
                .divider()
                .add(ID_MOVE, R.drawable.ic_move, activity.getString(R.string.note_move))
                .add(ID_DUPLICATE, R.drawable.ic_copy,
                        activity.getString(R.string.note_duplicate))
                .add(ID_PIN, R.drawable.ic_pin, activity.getString(
                        note.isPinned() ? R.string.note_unpin : R.string.note_pin))
                .add(ID_STAR, note.isBookmarked()
                                ? R.drawable.ic_star : R.drawable.ic_star_outline,
                        activity.getString(note.isBookmarked()
                                ? R.string.note_unstar : R.string.note_star))
                .add(ID_ARCHIVE, R.drawable.ic_archive, activity.getString(
                        note.isArchived() ? R.string.note_unarchive : R.string.note_archive))
                .divider()
                .add(ID_INFO, R.drawable.ic_info, activity.getString(R.string.note_info))
                .add(ID_PROPERTIES, R.drawable.ic_filter,
                        activity.getString(R.string.properties_title))
                .add(ID_RAW_MARKDOWN, R.drawable.ic_block_code,
                        activity.getString(R.string.raw_markdown))
                .add(ID_FIND_REPLACE, R.drawable.ic_search,
                        activity.getString(R.string.find_replace))
                .add(new AnchoredMenu.Item(ID_DELETE, R.drawable.ic_delete,
                        activity.getString(R.string.note_delete)).destructive())
                .onItem(this::handle)
                .showAt(anchor);
    }

    private void handle(int id) {
        final Note note = host.note();
        if (note == null) return;
        switch (id) {
            case ID_EXPORT:
                host.showExportMenu();
                break;
            case ID_HISTORY:
                HistoryActivity.start(activity, note.getId());
                break;
            case ID_GRAPH:
                GraphActivity.startFocused(activity, note.getId());
                break;
            case ID_RAW_MARKDOWN:
                host.toggleRawMarkdown();
                break;
            case ID_FIND_REPLACE:
                host.showFindReplace();
                break;
            case ID_OPENED_NOTES:
                host.showOpenedNotes();
                break;
            case ID_HEADINGS:
                host.showHeadingNavigator();
                break;
            case ID_OPEN_ALONGSIDE:
                host.showNoteAlongside();
                break;
            case ID_MOVE:
                showMoveDialog();
                break;
            case ID_DUPLICATE: {
                host.commit();
                Note copy = repo.duplicate(note);
                if (copy != null) host.openNote(copy.getId());
                break;
            }
            case ID_PIN:
                note.setPinned(!note.isPinned());
                repo.updateMeta(note);
                activity.toast(note.isPinned() ? R.string.note_pin : R.string.note_unpin);
                break;
            case ID_STAR:
                note.setBookmarked(!note.isBookmarked());
                repo.updateMeta(note);
                break;
            case ID_ARCHIVE:
                note.setArchived(!note.isArchived());
                repo.updateMeta(note);
                break;
            case ID_INFO:
                showInfo();
                break;
            case ID_DELETE:
                confirmDelete();
                break;
            case ID_PROPERTIES:
                host.commit();
                host.onPropertiesOpened();
                PropertiesActivity.start(activity, note.getId());
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- actions

    public void showMoveDialog() {
        final Note note = host.note();
        if (note == null) return;
        final List<String> folders = new ArrayList<>();
        folders.add("");
        folders.addAll(repo.index().allFolders());
        final CharSequence[] labels = new CharSequence[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = folders.get(i).isEmpty()
                    ? activity.getString(R.string.vault_root) : folders.get(i);
        }
        activity.dialogBuilder()
                .setTitle(R.string.note_move)
                .setItems(labels, (d, which) -> {
                    host.commit();
                    String newId = repo.move(note, folders.get(which));
                    if (newId != null) {
                        note.setId(newId);
                        host.refreshChrome();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public void showInfo() {
        final Note note = host.note();
        if (note == null) return;
        String message = activity.getString(R.string.info_words, note.getWordCount())
                + "\n" + activity.getString(R.string.info_characters,
                note.getContent() == null ? 0 : note.getContent().length())
                + "\n" + activity.getString(R.string.info_blocks, host.blockCount())
                + "\n\n" + activity.getString(R.string.info_created,
                RelativeTime.absolute(note.getCreatedMillis()))
                + "\n" + activity.getString(R.string.info_modified,
                RelativeTime.absolute(note.getModifiedMillis()))
                + "\n\n" + activity.getString(R.string.info_path, note.getId());
        activity.dialogBuilder()
                .setTitle(R.string.note_info)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** Emoji picker, shown as a horizontal strip beside the note's icon slot. */
    public void showIconPicker(View anchor) {
        AnchoredMenu menu = AnchoredMenu.horizontal(activity);
        for (int i = 0; i < ICONS.length; i++) {
            menu.add(new AnchoredMenu.Item(i, 0, ICONS[i]));
        }
        menu.add(new AnchoredMenu.Item(ICONS.length, 0, "✕"));
        menu.onItem(id -> {
            Note note = host.note();
            if (note == null) return;
            note.setEmoji(id >= ICONS.length ? "" : ICONS[id]);
            repo.updateMeta(note);
            host.refreshChrome();
        }).showAt(anchor);
    }

    public void confirmDelete() {
        if (!prefs.confirmDelete()) {
            deleteNow();
            return;
        }
        final Note note = host.note();
        if (note == null) return;
        activity.confirmAction(R.string.delete_note_q,
                activity.getString(R.string.delete_note_body, note.getTitle(),
                        prefs.trashRetentionDays()),
                R.string.note_delete, this::deleteNow);
    }

    private void deleteNow() {
        Note note = host.note();
        if (note == null) return;
        host.cancelPendingSave();
        repo.delete(note);
        prefs.forgetRecent(note.getId());
        activity.toast(R.string.moved_to_trash);
        host.onNoteDeleted();
    }
}
