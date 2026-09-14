package com.ccs.shard.editor;

import android.content.Intent;
import android.net.Uri;

import com.ccs.shard.R;
import com.ccs.shard.TexEditorActivity;
import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.io.Importer;
import com.ccs.shard.ui.CommandPalette;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles the editor being opened by something other than the app itself: a
 * Markdown file opened from a file manager, or text shared from another app.
 *
 * <p>Both paths are fiddly for the same reason — they must survive a rotation
 * without acting twice, so each clears the intent's action as soon as it takes
 * responsibility for it. Keeping that reasoning in one place, away from the
 * editor's own lifecycle, is why this is its own class.
 */
public final class EditorIntents {

    /** What this class needs from the editor screen. */
    public interface Host {
        /** Opens {@code noteId} in this screen, replacing whatever was loading. */
        void loadNoteId(String noteId);
        /** Adopts an already-loaded note and renders {@code markdown}. */
        void adoptNote(Note note, String markdown);
        void showSavingStatus();
        void close();
    }

    private final BaseActivity activity;
    private final VaultRepository repo;
    private final Prefs prefs;
    private final Host host;

    public EditorIntents(BaseActivity activity, VaultRepository repo, Prefs prefs, Host host) {
        this.activity = activity;
        this.repo = repo;
        this.prefs = prefs;
        this.host = host;
    }

    /**
     * @return true when the intent was consumed, meaning the screen must not run
     *         its normal "load the note in EXTRA_NOTE_ID" path
     */
    public boolean handle(Intent intent) {
        return handleSharedText(intent) || handleViewedFile(intent);
    }

    /** Imports a file opened through Android, then routes it to its native editor. */
    private boolean handleViewedFile(final Intent intent) {
        Uri uri = intent.getData();
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || uri == null) return false;
        intent.setAction(null); // Rotation must not import a second copy.
        host.showSavingStatus();

        new Importer(activity, repo).importFiles(Collections.singletonList(uri), "",
                new Importer.Callback() {
                    @Override public void onProgress(int done, int total, String name) {}

                    @Override public void onFinished(Importer.Report report) {
                        if (report.noteIds.isEmpty()) {
                            fail();
                            return;
                        }
                        String id = report.noteIds.get(0);
                        if (NoteFile.isTexFile(id)) {
                            TexEditorActivity.open(activity, id);
                            host.close();
                        } else {
                            host.loadNoteId(id);
                        }
                    }

                    @Override public void onFailed(Throwable error) {
                        fail();
                    }

                    private void fail() {
                        activity.toast(R.string.import_failed);
                        host.close();
                    }
                });
        return true;
    }

    /** Routes {@code ACTION_SEND} text into a new or an existing note. */
    private boolean handleSharedText(final Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())
                || !"text/plain".equals(intent.getType())) {
            return false;
        }
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null || shared.toString().trim().isEmpty()) return false;

        final String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        // Clear the action now so rotation cannot append the same share twice.
        intent.setAction(null);
        final String text = shared.toString().trim();
        repo.open(() -> chooseShareTarget(text, subject));
        return true;
    }

    /** Asks where the shared text should go: a new note, or appended to one. */
    private void chooseShareTarget(final String text, final String subject) {
        List<CommandPalette.Action> actions = new ArrayList<>();
        actions.add(new CommandPalette.Action(R.drawable.ic_add,
                activity.getString(R.string.share_new_note), "new create",
                () -> createFromShare(text, subject)));

        List<Note> notes = repo.notes();
        Collections.sort(notes, (a, b) ->
                Long.compare(b.getModifiedMillis(), a.getModifiedMillis()));
        for (final Note target : notes) {
            String label = target.getEmoji().isEmpty() ? target.getTitle()
                    : target.getEmoji() + "  " + target.getTitle();
            actions.add(new CommandPalette.Action(R.drawable.ic_notes, label,
                    target.folder(), () -> appendToNote(target.getId(), text)));
        }
        CommandPalette.show(activity, actions, host::close);
    }

    private void createFromShare(String text, String subject) {
        String title = subject == null || subject.trim().isEmpty()
                ? activity.getString(R.string.shared_note_title) : subject.trim();
        Note created = repo.createNote(title, "", text);
        prefs.pushRecent(created.getId());
        host.adoptNote(created, text);
    }

    private void appendToNote(final String noteId, final String shared) {
        repo.loadNote(noteId, new Io.Result<Note>() {
            @Override public void onReady(Note loaded) {
                String existing = loaded.getContent() == null ? "" : loaded.getContent();
                String updated = existing.trim().isEmpty() ? shared
                        : existing + (existing.endsWith("\n") ? "\n" : "\n\n") + shared;
                loaded.setContent(updated);
                repo.save(loaded, true);

                if (NoteFile.isTexFile(loaded.getId())) {
                    TexEditorActivity.open(activity, loaded.getId());
                    host.close();
                    return;
                }
                prefs.pushRecent(loaded.getId());
                host.adoptNote(loaded, updated);
                activity.toast(R.string.share_added);
            }

            @Override public void onError(Throwable error) {
                activity.toast(R.string.note_not_found);
                host.close();
            }
        });
    }
}
