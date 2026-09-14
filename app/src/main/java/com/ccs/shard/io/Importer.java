package com.ccs.shard.io;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.ccs.shard.core.CanvasStore;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.Vault;
import com.ccs.shard.core.VaultRepository;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Brings existing notes into the vault.
 *
 * <p>Handles the four shapes people actually have:
 * <ul>
 *   <li><b>Individual files</b> picked from anywhere — {@code .md}, {@code .txt},
 *       {@code .markdown}, {@code .tex}, or a {@code .csv} table.</li>
 *   <li><b>A folder</b>, walked recursively through the document tree. This is
 *       the path for an Obsidian vault: folder structure, attachments and
 *       {@code [[wiki links]]} all survive.</li>
 *   <li><b>A ZIP archive</b>, including Notion's Markdown export.</li>
 *   <li><b>Notion exports</b> specifically: the 32-character page id Notion
 *       appends to every file name is stripped, and its relative
 *       {@code [Text](Page%20abc123.md)} links are rewritten as wiki links so the
 *       graph actually connects.</li>
 * </ul>
 *
 * <p>Nothing is overwritten: a name clash gets a numeric suffix, so importing
 * twice is safe.
 */
public final class Importer {

    private static final String TAG = "ShardImport";
    /** Guard against a pathological archive filling the device. */
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final int MAX_FILES = 20000;
    private static final int MAX_SINGLE_FILE_BYTES = 64 * 1024 * 1024;

    /** Notion appends a 32-hex-character page id to every exported file name. */
    private static final Pattern NOTION_SUFFIX =
            Pattern.compile("[ _-]?[0-9a-f]{32}$");
    private static final Pattern NOTION_LINK =
            Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+?\\.md)\\)");

    public interface Callback {
        /** Progress ticks on the main thread; {@code total} may be 0 when unknown. */
        void onProgress(int done, int total, String currentName);
        void onFinished(Report report);
        void onFailed(Throwable error);
    }

    /** What an import produced. */
    public static final class Report {
        public final int notesImported;
        public final int canvasesImported;
        public final int attachmentsImported;
        public final int skipped;
        public final List<String> warnings;
        /** Vault-relative ids of notes created by this import, in source order. */
        public final List<String> noteIds;

        Report(int notes, int canvases, int attachments, int skipped, List<String> warnings,
               List<String> noteIds) {
            this.notesImported = notes;
            this.canvasesImported = canvases;
            this.attachmentsImported = attachments;
            this.skipped = skipped;
            this.warnings = warnings;
            this.noteIds = Collections.unmodifiableList(new ArrayList<>(noteIds));
        }
    }

    public static final class Preview {
        public int notes;
        public int canvases;
        public int attachments;
        public int skipped;
        public long unpackedBytes;
        public final List<String> examples = new ArrayList<>();
    }

    public interface PreviewCallback {
        void onReady(Preview preview);
        void onFailed(Throwable error);
    }

    private final Context context;
    private final VaultRepository repository;
    private final Vault vault;

    public Importer(Context context, VaultRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.vault = repository.vault();
    }

    // ---------------------------------------------------------------- entry points

    /** Imports individual picked files into {@code targetFolder}. */
    public void importFiles(final List<Uri> uris, final String targetFolder,
                            final Callback callback) {
        start(callback, new Job() {
            @Override public Report run(Progress progress) throws Exception {
                Session session = new Session(targetFolder);
                int index = 0;
                for (Uri uri : uris) {
                    String name = displayName(uri);
                    progress.tick(++index, uris.size(), name);
                    byte[] data = readAll(context.getContentResolver().openInputStream(uri),
                            true, MAX_SINGLE_FILE_BYTES);
                    if (data == null) {
                        session.skipped++;
                        continue;
                    }
                    session.consume(name, data);
                }
                session.finish();
                return session.report();
            }
        });
    }

    /** Imports a whole folder tree picked with {@code ACTION_OPEN_DOCUMENT_TREE}. */
    public void importFolder(final Uri treeUri, final String targetFolder,
                             final Callback callback) {
        start(callback, new Job() {
            @Override public Report run(Progress progress) throws Exception {
                DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
                if (root == null) throw new IOException("cannot open folder");
                String baseName = root.getName();
                String base = targetFolder;
                if (baseName != null && !baseName.isEmpty()) {
                    base = joinFolder(targetFolder, Md.safeFileName(baseName));
                }
                Session session = new Session(base);
                List<Pending> queue = new ArrayList<>();
                collect(root, "", queue);
                int index = 0;
                for (Pending pending : queue) {
                    progress.tick(++index, queue.size(), pending.name);
                    byte[] data = readAll(
                            context.getContentResolver().openInputStream(pending.file.getUri()),
                            true, MAX_SINGLE_FILE_BYTES);
                    if (data == null) {
                        session.skipped++;
                        continue;
                    }
                    session.consume(pending.relativeFolder, pending.name, data);
                }
                session.finish();
                return session.report();
            }
        });
    }

    /** Imports the contents of a ZIP archive. */
    public void importZip(final Uri zipUri, final String targetFolder, final Callback callback) {
        start(callback, new Job() {
            @Override public Report run(Progress progress) throws Exception {
                String archiveName = displayName(zipUri);
                String stem = archiveName == null ? "import" : stripExtension(archiveName);
                Session session = new Session(joinFolder(targetFolder, Md.safeFileName(stem)));
                InputStream in = context.getContentResolver().openInputStream(zipUri);
                if (in == null) throw new IOException("cannot open archive");
                ZipInputStream zip = new ZipInputStream(in);
                try {
                    ZipEntry entry;
                    int index = 0;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (session.totalBytes > MAX_TOTAL_BYTES
                                || session.notes + session.canvases
                                + session.attachments > MAX_FILES) {
                            session.warnings.add("Archive truncated: too large");
                            break;
                        }
                        if (entry.isDirectory()) continue;
                        String path = sanitizeZipPath(entry.getName());
                        if (path == null) {
                            session.skipped++;
                            continue;
                        }
                        progress.tick(++index, 0, path);
                        int remaining = (int) Math.min(MAX_SINGLE_FILE_BYTES,
                                Math.max(0L, MAX_TOTAL_BYTES - session.totalBytes));
                        byte[] data = readAll(zip, false, remaining);
                        if (data == null) {
                            session.warnings.add("Skipped oversized file: " + path);
                            session.skipped++;
                            continue;
                        }
                        int slash = path.lastIndexOf('/');
                        String folder = slash < 0 ? "" : path.substring(0, slash);
                        String name = slash < 0 ? path : path.substring(slash + 1);
                        session.consume(folder, name, data);
                    }
                } finally {
                    closeQuietly(zip);
                }
                session.finish();
                return session.report();
            }
        });
    }

    /** Reads archive metadata and a bounded sample before the user commits to importing it. */
    public void previewZip(final Uri zipUri, final PreviewCallback callback) {
        Io.load(new Io.Task<Preview>() {
            @Override public Preview run() throws Exception {
                InputStream input = context.getContentResolver().openInputStream(zipUri);
                if (input == null) throw new IOException("cannot open archive");
                return inspectZip(input);
            }
        }, new Io.Result<Preview>() {
            @Override public void onReady(Preview value) { callback.onReady(value); }
            @Override public void onError(Throwable error) { callback.onFailed(error); }
        });
    }

    /** Shared by production preview and large-archive regression tests. */
    static Preview inspectZip(InputStream input) throws IOException {
        Preview preview = new Preview();
        ZipInputStream zip = new ZipInputStream(input);
        try {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            int files = 0;
            while ((entry = zip.getNextEntry()) != null && files < MAX_FILES) {
                if (entry.isDirectory()) continue;
                files++;
                String path = sanitizeZipPath(entry.getName());
                if (path == null) { preview.skipped++; continue; }
                String lower = path.toLowerCase(Locale.ROOT);
                if (NoteFile.isNoteFile(lower) || lower.endsWith(".csv")) preview.notes++;
                else if (lower.endsWith(CanvasStore.EXT)) preview.canvases++;
                else preview.attachments++;
                if (preview.examples.size() < 8) preview.examples.add(path);
                long declared = entry.getSize();
                if (declared >= 0) preview.unpackedBytes += declared;
                else {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        preview.unpackedBytes += read;
                        if (preview.unpackedBytes > MAX_TOTAL_BYTES) break;
                    }
                }
                if (preview.unpackedBytes > MAX_TOTAL_BYTES) break;
            }
        } finally {
            closeQuietly(zip);
        }
        return preview;
    }

    // ---------------------------------------------------------------- session

    /**
     * Accumulates one import run: writes notes and attachments, remembers the
     * Notion name mapping, then rewrites links once every file name is known.
     */
    private final class Session {
        final String baseFolder;
        final List<String> warnings = new ArrayList<>();
        /** Imported note file -> its final vault-relative id. */
        final List<String> importedIds = new ArrayList<>();
        final List<String> allImportedIds = new ArrayList<>();
        /** Original file name (as written in links) -> cleaned note title. */
        final Map<String, String> linkMap = new HashMap<>();

        int notes;
        int canvases;
        int attachments;
        int skipped;
        long totalBytes;

        Session(String baseFolder) {
            this.baseFolder = baseFolder == null ? "" : baseFolder;
        }

        void consume(String name, byte[] data) throws IOException {
            consume("", name, data);
        }

        void consume(String relativeFolder, String name, byte[] data) throws IOException {
            totalBytes += data.length;
            String lower = name.toLowerCase(Locale.ROOT);
            String folder = joinFolder(baseFolder, relativeFolder);

            if (NoteFile.isNoteFile(lower)) {
                String rawTitle = stripExtension(name);
                String cleanTitle = cleanNotionName(rawTitle);
                String body = new String(data, "UTF-8");
                String extension = lower.endsWith(NoteFile.TEX_EXT)
                        ? NoteFile.TEX_EXT : NoteFile.EXT;
                String id = writeNote(folder, cleanTitle, body, extension);
                if (id != null) {
                    notes++;
                    allImportedIds.add(id);
                    // Notion's Markdown link rewriting must never touch TeX source.
                    if (!NoteFile.isTexFile(id)) {
                        importedIds.add(id);
                        linkMap.put(name, cleanTitle);
                        linkMap.put(rawTitle, cleanTitle);
                    }
                }
                return;
            }
            if (lower.endsWith(".csv")) {
                String rawTitle = stripExtension(name);
                String cleanTitle = cleanNotionName(rawTitle);
                String body = csvToMarkdownTable(new String(data, "UTF-8"));
                String id = writeNote(folder, cleanTitle, body, NoteFile.EXT);
                if (id != null) {
                    notes++;
                    allImportedIds.add(id);
                    importedIds.add(id);
                }
                return;
            }
            if (lower.endsWith(CanvasStore.EXT)) {
                String title = stripExtension(name);
                String id = writeCanvas(folder, title, new String(data, "UTF-8"));
                if (id != null) canvases++;
                return;
            }
            if (isAttachment(lower)) {
                try {
                    repository.importAttachment(name, data);
                    attachments++;
                } catch (IOException e) {
                    warnings.add("Could not save " + name);
                    skipped++;
                }
                return;
            }
            skipped++;
        }

        /** Writes a note, never overwriting an existing one. */
        private String writeNote(String folder, String title, String body, String extension)
                throws IOException {
            String safe = Md.safeFileName(title);
            String prefix = folder.isEmpty() ? "" : folder + "/";
            String id = prefix + safe + extension;
            File file = vault.resolve(id);
            int n = 2;
            while (file.exists()) {
                if (n >= 10_000) throw new IOException("Too many notes named " + safe);
                id = prefix + safe + " " + n + extension;
                file = vault.resolve(id);
                n++;
            }
            NoteFile.writeAtomic(file, body);
            return id;
        }

        /** Writes a JSON Canvas file without normalising another app's JSON. */
        private String writeCanvas(String folder, String title, String json) throws IOException {
            String safe = Md.safeFileName(title);
            String prefix = folder.isEmpty() ? "" : folder + "/";
            String id = prefix + safe + CanvasStore.EXT;
            File file = vault.resolve(id);
            int n = 2;
            while (file.exists()) {
                if (n >= 10_000) throw new IOException("Too many canvases named " + safe);
                id = prefix + safe + " " + n + CanvasStore.EXT;
                file = vault.resolve(id);
                n++;
            }
            NoteFile.writeAtomic(file, json);
            return id;
        }

        /** Second pass: convert Notion-style relative links into wiki links. */
        void finish() {
            if (linkMap.isEmpty()) return;
            for (String id : importedIds) {
                File file = vault.resolve(id);
                try {
                    String body = NoteFile.readText(file);
                    String rewritten = rewriteLinks(body, linkMap);
                    if (!rewritten.equals(body)) NoteFile.writeAtomic(file, rewritten);
                } catch (Throwable t) {
                    Log.w(TAG, "cannot rewrite links in " + id, t);
                }
            }
        }

        Report report() {
            return new Report(notes, canvases, attachments, skipped, warnings,
                    allImportedIds);
        }
    }

    /** Rewrites {@code [Text](Some%20Page%20abc.md)} to {@code [[Some Page|Text]]}. */
    private static String rewriteLinks(String body, Map<String, String> linkMap) {
        Matcher m = NOTION_LINK.matcher(body);
        if (!m.find()) return body;
        StringBuilder out = new StringBuilder(body.length());
        int last = 0;
        do {
            String label = m.group(1);
            String target = decode(m.group(2));
            int slash = target.lastIndexOf('/');
            String fileName = slash < 0 ? target : target.substring(slash + 1);
            String title = linkMap.get(fileName);
            if (title == null) title = linkMap.get(stripExtension(fileName));
            if (title == null) title = cleanNotionName(stripExtension(fileName));
            out.append(body, last, m.start());
            if (label.equals(title)) {
                out.append("[[").append(title).append("]]");
            } else {
                out.append("[[").append(title).append('|').append(label).append("]]");
            }
            last = m.end();
        } while (m.find());
        out.append(body, last, body.length());
        return out.toString();
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Throwable t) {
            return value;
        }
    }

    /** Strips Notion's trailing page id from a file name. */
    static String cleanNotionName(String name) {
        String cleaned = NOTION_SUFFIX.matcher(name).replaceAll("").trim();
        return cleaned.isEmpty() ? name : cleaned;
    }

    /** Renders a CSV (e.g. a Notion database export) as a Markdown table. */
    static String csvToMarkdownTable(String csv) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) return "";
        int columns = 0;
        for (List<String> row : rows) columns = Math.max(columns, row.size());
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            out.append('|');
            for (int c = 0; c < columns; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                out.append(' ').append(cell.replace("|", "\\|").replace("\n", " ")).append(" |");
            }
            out.append('\n');
            if (r == 0) {
                out.append('|');
                for (int c = 0; c < columns; c++) out.append(" --- |");
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- walking

    private static final class Pending {
        final DocumentFile file;
        final String relativeFolder;
        final String name;

        Pending(DocumentFile file, String relativeFolder, String name) {
            this.file = file;
            this.relativeFolder = relativeFolder;
            this.name = name;
        }
    }

    private static void collect(DocumentFile dir, String relativeFolder, List<Pending> out) {
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null || name.startsWith(".")) continue;
            if (child.isDirectory()) {
                if (out.size() >= MAX_FILES) return;
                collect(child, joinFolder(relativeFolder, Md.safeFileName(name)), out);
            } else {
                if (out.size() >= MAX_FILES) return;
                out.add(new Pending(child, relativeFolder, name));
            }
        }
    }

    static int countImportableTreeForTest(DocumentFile root) {
        List<Pending> pending = new ArrayList<>();
        collect(root, "", pending);
        return pending.size();
    }

    // ---------------------------------------------------------------- plumbing

    private interface Job {
        Report run(Progress progress) throws Exception;
    }

    private interface Progress {
        void tick(int done, int total, String name);
    }

    private void start(final Callback callback, final Job job) {
        Io.onDisk(new Runnable() {
            @Override public void run() {
                try {
                    Report report = job.run(new Progress() {
                        @Override public void tick(final int done, final int total,
                                                   final String name) {
                            if (callback == null) return;
                            Io.onMain(new Runnable() {
                                @Override public void run() {
                                    callback.onProgress(done, total, name);
                                }
                            });
                        }
                    });
                    repository.refresh();
                    final Report finalReport = report;
                    if (callback != null) {
                        Io.onMain(new Runnable() {
                            @Override public void run() { callback.onFinished(finalReport); }
                        });
                    }
                } catch (final Throwable t) {
                    Log.e(TAG, "import failed", t);
                    if (callback != null) {
                        Io.onMain(new Runnable() {
                            @Override public void run() { callback.onFailed(t); }
                        });
                    }
                }
            }
        });
    }

    private String displayName(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        android.database.Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the path-based guess.
        } finally {
            if (cursor != null) cursor.close();
        }
        String path = uri.getLastPathSegment();
        if (path == null) return "imported.md";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static boolean isAttachment(String lowerName) {
        return lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".gif")
                || lowerName.endsWith(".webp") || lowerName.endsWith(".bmp")
                || lowerName.endsWith(".pdf") || lowerName.endsWith(".svg");
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String joinFolder(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + "/" + b;
    }

    /** Rejects absolute paths and {@code ..} escapes from a malicious archive. */
    static String sanitizeZipPath(String entryName) {
        if (entryName == null) return null;
        String normalised = entryName.replace('\\', '/');
        if (normalised.startsWith("/")) normalised = normalised.substring(1);
        String[] parts = normalised.split("/");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (part.startsWith("__MACOSX")) return null;
            if (out.length() > 0) out.append('/');
            out.append(Md.safeFileName(part));
        }
        String result = out.toString();
        return result.isEmpty() ? null : result;
    }

    private static byte[] readAll(InputStream in) {
        return readAll(in, true, MAX_SINGLE_FILE_BYTES);
    }

    private static byte[] readAll(InputStream in, boolean close) {
        return readAll(in, close, MAX_SINGLE_FILE_BYTES);
    }

    static byte[] readAll(InputStream in, boolean close, int maxBytes) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (close) closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) { }
    }

    /** Copies an exported file to a location the user picked. */
    public static boolean copyToUri(Context context, File source, Uri target) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new java.io.FileInputStream(source);
            out = context.getContentResolver().openOutputStream(target);
            if (out == null) return false;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "cannot copy to " + target, t);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }
}
