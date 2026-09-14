package com.ccs.shard.io;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.ccs.shard.R;
import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.ui.AnchoredMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole "export something, then decide where it goes" interaction.
 *
 * <p>Two screens need it — the note editor and the vault browser — and both had
 * their own copy of the format menu, the destination menu, the
 * {@code ACTION_CREATE_DOCUMENT} launcher and the {@code pendingExport} field.
 * That was about a hundred duplicated lines and two places for the two menus to
 * drift apart, which they had already started doing.
 *
 * <p>Construct one in {@code onCreate}: it registers an activity-result launcher,
 * which must happen before the activity starts.
 */
public final class ExportFlow {

    private static final int ID_SHARE = 1;
    private static final int ID_SAVE_TO = 2;

    private final BaseActivity activity;
    private final VaultRepository repo;
    private final ActivityResultLauncher<Intent> saveLauncher;
    private final Prefs prefs;

    /** Held between choosing "Save to…" and the picker returning a destination. */
    private Exporter.Result pending;
    private Runnable beforeExport;

    public ExportFlow(final BaseActivity activity, VaultRepository repo) {
        this.activity = activity;
        this.repo = repo;
        this.prefs = new Prefs(activity);
        this.saveLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK
                            || result.getData() == null) {
                        return;
                    }
                    Uri target = result.getData().getData();
                    if (target == null || pending == null) return;
                    prefs.setLastExportUri(target.toString());
                    final Exporter.Result export = pending;
                    pending = null;
                    copyToDestination(export, target);
                });
    }

    /**
     * Runs before every export — the editor uses it to flush unsaved edits, so
     * what gets exported is what is on screen.
     */
    public ExportFlow beforeExport(Runnable action) {
        this.beforeExport = action;
        return this;
    }

    // ---------------------------------------------------------------- entry points

    /** Offers the formats that make sense for {@code note}, then exports it. */
    public void offerNoteFormats(final Note note, final View anchor) {
        if (note == null) return;
        AnchoredMenu menu = AnchoredMenu.vertical(activity)
                .title(activity.getString(R.string.export_note));

        // A TeX note has no meaningful Markdown or HTML rendering, so it is
        // offered as its own source plus an archive.
        boolean isTex = NoteFile.isTexFile(note.getId());
        if (isTex) {
            menu.add(1, R.drawable.ic_block_code,
                    activity.getString(R.string.export_tex_source));
        } else {
            menu.add(1, R.drawable.ic_block_text,
                            activity.getString(R.string.export_as_markdown))
                    .add(2, R.drawable.ic_block_image,
                            activity.getString(R.string.export_as_pdf))
                    .add(3, R.drawable.ic_open_in_new,
                            activity.getString(R.string.export_as_html))
                    .add(4, R.drawable.ic_block_text,
                            activity.getString(R.string.export_as_text));
        }
        menu.add(5, R.drawable.ic_backup, activity.getString(R.string.export_as_zip));

        menu.onItem(id -> exportNote(note, formatFor(id), anchor)).showAt(anchor);
    }

    private static Exporter.Format formatFor(int id) {
        switch (id) {
            case 2: return Exporter.Format.PDF;
            case 3: return Exporter.Format.HTML;
            case 4: return Exporter.Format.PLAIN_TEXT;
            case 5: return Exporter.Format.ZIP;
            default: return Exporter.Format.MARKDOWN;
        }
    }

    public void exportNote(final Note note, Exporter.Format format, final View anchor) {
        if (beforeExport != null) beforeExport.run();
        new Exporter(repo).exportNote(note, format, callback(note.getTitle(), anchor));
    }

    /** Offers formats suitable for a multi-selection. */
    public void offerNotes(final List<Note> notes, final View anchor) {
        if (notes == null || notes.isEmpty()) return;
        final List<Note> selected = new ArrayList<>(notes);
        AnchoredMenu.vertical(activity)
                .title(activity.getString(R.string.bulk_export_title))
                .add(1, R.drawable.ic_block_text,
                        activity.getString(R.string.bulk_export_markdown))
                .add(2, R.drawable.ic_backup,
                        activity.getString(R.string.bulk_export_zip))
                .onItem(id -> {
                    Exporter.Callback callback = callback(
                            activity.getString(R.string.bulk_export_subject), anchor);
                    if (id == 1) {
                        new Exporter(repo).exportCombined(selected, callback);
                    } else if (id == 2) {
                        new Exporter(repo).exportNotes(selected, callback);
                    }
                })
                .showAt(anchor);
    }

    /** Exports a folder, or the whole vault when {@code folder} is empty. */
    public void exportFolder(String folder, CharSequence label, final View anchor) {
        new Exporter(repo).exportFolder(folder, callback(label, anchor));
    }

    /** Full-vault ZIP backup: the guarantee that the user can always walk away. */
    public void exportVault(final View anchor, final Runnable onSuccess) {
        new Exporter(repo).exportVault(new Exporter.Callback() {
            @Override public void onExported(Exporter.Result result) {
                if (onSuccess != null) onSuccess.run();
                offerDestination(result, activity.getString(R.string.app_name), anchor);
            }

            @Override public void onFailed(Throwable error) {
                activity.toast(R.string.export_failed);
            }
        });
    }

    // ---------------------------------------------------------------- destination

    private Exporter.Callback callback(final CharSequence subject, final View anchor) {
        return new Exporter.Callback() {
            @Override public void onExported(Exporter.Result result) {
                offerDestination(result, subject, anchor);
            }

            @Override public void onFailed(Throwable error) {
                activity.toast(R.string.export_failed);
            }
        };
    }

    /** Share it now, or hand it to the document picker to keep. */
    private void offerDestination(final Exporter.Result result, final CharSequence subject,
                                  View anchor) {
        AnchoredMenu.vertical(activity)
                .title(result.file.getName())
                .add(ID_SHARE, R.drawable.ic_share, activity.getString(R.string.export_share))
                .add(ID_SAVE_TO, R.drawable.ic_export,
                        activity.getString(R.string.export_save_to))
                .onItem(id -> {
                    if (id == ID_SHARE) {
                        activity.shareFile(result.file, result.mimeType, subject);
                        return;
                    }
                    pending = result;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(result.mimeType);
                    intent.putExtra(Intent.EXTRA_TITLE, result.file.getName());
                    String saved = prefs.lastExportUri();
                    if (saved != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(saved));
                    }
                    saveLauncher.launch(intent);
                })
                .showAt(anchor);
    }

    private void copyToDestination(final Exporter.Result export, final Uri target) {
        Io.onDisk(() -> {
            final boolean ok = Importer.copyToUri(activity, export.file, target);
            Io.onMain(() -> activity.toast(
                    ok ? R.string.exported_ok : R.string.export_failed));
        });
    }
}
