package com.ccs.shard.io;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.ccs.shard.R;
import com.ccs.shard.NoteEditorActivity;
import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Prefs;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.ui.AnchoredMenu;

import java.util.List;

/**
 * The "bring notes in from somewhere else" interaction: the source menu, the
 * document pickers, the ZIP preview and the progress reporting.
 *
 * <p>Owns its three activity-result launchers and the little bit of state that
 * makes them work — notably which kind of file the picker was opened for, since
 * one launcher serves both "pick a ZIP" and "pick some Markdown". Construct in
 * {@code onCreate}, because registering a launcher later throws.
 */
public final class ImportFlow {

    private static final int ID_FOLDER = 1;
    private static final int ID_ZIP = 2;
    private static final int ID_FILES = 3;
    private static final int ID_OPEN_FILE = 4;

    /** What this class needs from the screen hosting it. */
    public interface Host {
        /** Folder new notes should land in, or {@code ""} for the vault root. */
        String targetFolder();
        void setBusy(boolean busy);
        /** An import finished; refresh whatever is on screen. */
        void onImported();
        /**
         * Last chance to refuse — the vault browser uses it to make sure the vault
         * is somewhere writable before opening a picker.
         *
         * @return false to abort
         */
        boolean canImport();
        void dismissChrome();
    }

    private final BaseActivity activity;
    private final VaultRepository repo;
    private final Host host;

    private final ActivityResultLauncher<Uri> folderPicker;
    private final ActivityResultLauncher<Intent> filePicker;
    private final Prefs prefs;

    /** True while the file picker is open for a ZIP rather than plain notes. */
    private boolean pickingZip;
    private boolean openingFile;

    public ImportFlow(BaseActivity activity, VaultRepository repo, Host host) {
        this.activity = activity;
        this.repo = repo;
        this.host = host;
        this.prefs = new Prefs(activity);

        folderPicker = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        prefs.setLastImportUri(uri.toString());
                        importer().importFolder(uri, "", callback());
                    }
                });
        filePicker = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK
                            || result.getData() == null) return;
                    Intent data = result.getData();
                    List<Uri> uris = selectedUris(data);
                    if (uris == null || uris.isEmpty()) return;
                    prefs.setLastImportUri(uris.get(0).toString());
                    if (openingFile) {
                        openingFile = false;
                        Intent open = new Intent(activity, NoteEditorActivity.class)
                                .setAction(Intent.ACTION_VIEW)
                                .setData(uris.get(0))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(open);
                        return;
                    }
                    if (pickingZip) {
                        pickingZip = false;
                        previewZip(uris.get(0), host.targetFolder());
                    } else {
                        importer().importFiles(uris, host.targetFolder(), callback());
                    }
                });
    }

    private Importer importer() {
        return new Importer(activity, repo);
    }

    // ---------------------------------------------------------------- menu

    /** Offers the three sources people actually have. */
    public void showSourceMenu(View anchor) {
        if (!host.canImport()) return;
        AnchoredMenu.vertical(activity)
                .title(activity.getString(R.string.nav_import))
                .add(new AnchoredMenu.Item(ID_OPEN_FILE, R.drawable.ic_folder_open,
                        activity.getString(R.string.open_file_picker))
                        .hint(activity.getString(R.string.open_file_picker_hint)))
                .add(new AnchoredMenu.Item(ID_FOLDER, R.drawable.ic_folder_open,
                        activity.getString(R.string.import_from_folder))
                        .hint(activity.getString(R.string.import_from_folder_hint)))
                .add(new AnchoredMenu.Item(ID_ZIP, R.drawable.ic_backup,
                        activity.getString(R.string.import_from_zip))
                        .hint(activity.getString(R.string.import_from_zip_hint)))
                .add(new AnchoredMenu.Item(ID_FILES, R.drawable.ic_block_text,
                        activity.getString(R.string.import_files))
                        .hint(activity.getString(R.string.import_files_hint)))
                .onItem(id -> {
                    host.dismissChrome();
                    launch(id);
                })
                .showAt(anchor);
    }

    private void launch(int id) {
        switch (id) {
            case ID_FOLDER:
                folderPicker.launch(savedImportUri());
                break;
            case ID_ZIP:
                pickingZip = true;
                // "*/*" is included because many file providers report an archive
                // with a vendor-specific MIME type and would otherwise grey it out.
                openingFile = false;
                launchFilePicker(true, new String[]{"application/zip",
                        "application/x-zip-compressed", "*/*"});
                break;
            case ID_FILES:
                pickingZip = false;
                openingFile = false;
                launchFilePicker(true, new String[]{"text/*", "*/*"});
                break;
            case ID_OPEN_FILE:
                pickingZip = false;
                openingFile = true;
                launchFilePicker(false, new String[]{"text/markdown", "text/x-markdown",
                        "application/x-tex", "text/plain", "*/*"});
                break;
            default:
                break;
        }
    }

    private Uri savedImportUri() {
        try {
            String saved = prefs.lastImportUri();
            return saved == null ? null : Uri.parse(saved);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void launchFilePicker(boolean multiple, String[] mimeTypes) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeTypes.length == 1 ? mimeTypes[0] : "*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        Uri initial = savedImportUri();
        if (initial != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }
        filePicker.launch(intent);
    }

    private static List<Uri> selectedUris(Intent data) {
        List<Uri> out = new java.util.ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !out.contains(uri)) out.add(uri);
            }
        }
        Uri single = data.getData();
        if (single != null && !out.contains(single)) out.add(single);
        return out;
    }

    /** Imports a tree or an archive picked elsewhere, e.g. from an empty state. */
    public void importUri(Uri uri, boolean isTree) {
        if (isTree) importer().importFolder(uri, "", callback());
        else importer().importZip(uri, "", callback());
    }

    // ---------------------------------------------------------------- preview

    /**
     * Shows what an archive contains before unpacking it. An import that silently
     * dumps four hundred files into a vault is hard to undo, so the count and a
     * few example names are worth a confirmation step.
     */
    private void previewZip(final Uri uri, final String targetFolder) {
        host.setBusy(true);
        final Importer importer = importer();
        importer.previewZip(uri, new Importer.PreviewCallback() {
            @Override public void onReady(Importer.Preview preview) {
                host.setBusy(false);
                activity.dialogBuilder()
                        .setTitle(R.string.import_preview_title)
                        .setMessage(describe(preview))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.nav_import, (d, which) ->
                                importer.importZip(uri, targetFolder, callback()))
                        .show();
            }

            @Override public void onFailed(Throwable error) {
                host.setBusy(false);
                activity.toast(R.string.import_failed);
            }
        });
    }

    private CharSequence describe(Importer.Preview preview) {
        StringBuilder message = new StringBuilder(activity.getString(
                R.string.import_preview_summary, preview.notes, preview.canvases,
                preview.attachments,
                Formatter.formatFileSize(activity, preview.unpackedBytes)));
        List<String> examples = preview.examples;
        if (!examples.isEmpty()) {
            message.append("\n\n");
            for (String name : examples) message.append("• ").append(name).append('\n');
        }
        return message;
    }

    // ---------------------------------------------------------------- reporting

    private Importer.Callback callback() {
        return new Importer.Callback() {
            @Override public void onProgress(int done, int total, String currentName) {
                host.setBusy(true);
            }

            @Override public void onFinished(Importer.Report report) {
                host.setBusy(false);
                activity.toast(report.canvasesImported > 0
                        ? activity.getString(R.string.import_done_with_canvases,
                                report.notesImported, report.canvasesImported)
                        : activity.getString(R.string.import_done, report.notesImported));
                host.onImported();
            }

            @Override public void onFailed(Throwable error) {
                host.setBusy(false);
                activity.toast(R.string.import_failed);
            }
        };
    }
}
