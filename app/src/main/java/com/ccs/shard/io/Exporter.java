package com.ccs.shard.io;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;

import com.ccs.shard.core.CanvasStore;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.Vault;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.editor.TexPreview;
import com.ccs.shard.editor.codeHighliter.HighlightTheme;
import com.ccs.shard.editor.codeHighliter.Highlighter;
import com.ccs.shard.editor.codeHighliter.LanguageDetector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns notes into files other apps can read: Markdown, HTML, PDF and ZIP.
 *
 * <p>Every entry point is asynchronous and reports through {@link Callback},
 * because rendering a long note to PDF is hundreds of milliseconds of text
 * layout that must never run on the main thread.
 *
 * <p>Output lands in the app's cache directory and is handed to the system via
 * {@code FileProvider}, so exporting needs no storage permission at all. The
 * caller then either shares it or copies it to a user-chosen location through the
 * document picker.
 */
public final class Exporter {

    private static final String TAG = "ShardExport";
    /** Standalone image line, used by the PDF renderer. */
    private static final Pattern IMAGE_PATTERN = Md.IMAGE;

    /** What an export produced. */
    public static final class Result {
        public final File file;
        public final String mimeType;
        public final int noteCount;

        Result(File file, String mimeType, int noteCount) {
            this.file = file;
            this.mimeType = mimeType;
            this.noteCount = noteCount;
        }
    }

    public interface Callback {
        void onExported(Result result);
        void onFailed(Throwable error);
    }

    private final VaultRepository repository;
    private final Vault vault;

    public Exporter(VaultRepository repository) {
        this.repository = repository;
        this.vault = repository.vault();
    }

    // ---------------------------------------------------------------- public API

    /** Exports one note in the requested format. */
    public void exportNote(final Note note, final Format format, final Callback callback) {
        run(callback, new Io.Task<Result>() {
            @Override public Result run() throws Exception {
                Note loaded = ensureLoaded(note);
                switch (format) {
                    case MARKDOWN: return new Result(writeMarkdown(loaded),
                            NoteFile.isTexFile(loaded.getId())
                                    ? "application/x-tex" : "text/markdown", 1);
                    case PLAIN_TEXT: return new Result(writePlainText(loaded), "text/plain", 1);
                    case HTML: {
                        File file = renderHtml(loaded);
                        if (file == null) throw new IOException("HTML export failed");
                        return new Result(file, "text/html", 1);
                    }
                    case PDF: {
                        File file = renderPdf(loaded);
                        if (file == null) throw new IOException("PDF export failed");
                        return new Result(file, "application/pdf", 1);
                    }
                    case ZIP: default: {
                        File file = zipNotes(java.util.Collections.singletonList(loaded),
                                Md.safeFileName(loaded.getTitle()));
                        return new Result(file, "application/zip", 1);
                    }
                }
            }
        });
    }

    /** Exports several selected notes as a folder-preserving ZIP archive. */
    public void exportNotes(final List<Note> notes, final Callback callback) {
        final List<Note> selected = notes == null
                ? new ArrayList<Note>() : new ArrayList<>(notes);
        run(callback, new Io.Task<Result>() {
            @Override public Result run() throws Exception {
                List<Note> loaded = new ArrayList<>(selected.size());
                for (Note note : selected) {
                    if (note != null) loaded.add(ensureLoaded(note));
                }
                File file = zipNotes(loaded, "shard-selected-notes");
                return new Result(file, "application/zip", loaded.size());
            }
        });
    }

    /** Exports a folder (or the whole vault when {@code folder} is empty) as a ZIP. */
    public void exportFolder(final String folder, final Callback callback) {
        run(callback, new Io.Task<Result>() {
            @Override public Result run() throws Exception {
                String prefix = folder == null ? "" : folder;
                List<Note> notes = new ArrayList<>();
                for (Note note : repository.index().all()) {
                    if (prefix.isEmpty() || note.getId().startsWith(prefix + "/")
                            || note.folder().equals(prefix)) {
                        notes.add(note);
                    }
                }
                List<CanvasStore.Entry> canvases = new ArrayList<>();
                for (CanvasStore.Entry entry : new CanvasStore(vault).list()) {
                    if (prefix.isEmpty() || entry.id.startsWith(prefix + "/")
                            || canvasFolder(entry.id).equals(prefix)) {
                        canvases.add(entry);
                    }
                }
                String name = prefix.isEmpty()
                        ? "shard-vault"
                        : Md.safeFileName(prefix.substring(prefix.lastIndexOf('/') + 1));
                File file = zipNotes(notes, canvases, name);
                return new Result(file, "application/zip", notes.size());
            }
        });
    }

    /** Full vault backup: every note and canvas, plus attachments. */
    public void exportVault(final Callback callback) {
        run(callback, new Io.Task<Result>() {
            @Override public Result run() throws Exception {
                List<Note> notes = repository.index().all();
                File file = zipVault(notes, new CanvasStore(vault).list());
                return new Result(file, "application/zip", notes.size());
            }
        });
    }

    /**
     * Blocking full-environment ZIP writer used by the scheduled backup job.
     * Unlike a portable manual export, this also preserves version history,
     * trash and sync sidecars so the local environment can be recovered exactly.
     */
    public Result exportVaultNow(File target) throws IOException {
        File file = zipEnvironment(vault.root(), target);
        return new Result(file, "application/zip", repository.index().size());
    }

    /** Concatenates several notes into one Markdown document. */
    public void exportCombined(final List<Note> notes, final Callback callback) {
        run(callback, new Io.Task<Result>() {
            @Override public Result run() throws Exception {
                StringBuilder out = new StringBuilder();
                for (Note note : notes) {
                    Note loaded = ensureLoaded(note);
                    out.append("# ").append(loaded.getTitle()).append("\n\n");
                    out.append(loaded.getContent() == null ? "" : loaded.getContent());
                    out.append("\n\n---\n\n");
                }
                File file = new File(vault.exportCacheDir(), "shard-notes.md");
                NoteFile.writeAtomic(file, out.toString());
                return new Result(file, "text/markdown", notes.size());
            }
        });
    }

    public enum Format { MARKDOWN, PLAIN_TEXT, HTML, PDF, ZIP }

    private void run(final Callback callback, final Io.Task<Result> task) {
        Io.load(task, new Io.Result<Result>() {
            @Override public void onReady(Result value) {
                if (callback != null) callback.onExported(value);
            }

            @Override public void onError(Throwable t) {
                Log.e(TAG, "export failed", t);
                if (callback != null) callback.onFailed(t);
            }
        });
    }

    private Note ensureLoaded(Note note) throws IOException {
        if (note.isLoaded()) return note;
        return repository.loadNoteSync(note.getId());
    }

    // ---------------------------------------------------------------- writers

    private File writeMarkdown(Note note) throws IOException {
        String extension = NoteFile.isTexFile(note.getId())
                ? NoteFile.TEX_EXT : NoteFile.EXT;
        File file = new File(vault.exportCacheDir(),
                Md.safeFileName(note.getTitle()) + extension);
        NoteFile.writeAtomic(file, NoteFile.serialize(note));
        return file;
    }

    private File writePlainText(Note note) throws IOException {
        File file = new File(vault.exportCacheDir(), Md.safeFileName(note.getTitle()) + ".txt");
        StringBuilder out = new StringBuilder();
        out.append(note.getTitle()).append('\n');
        for (int i = 0; i < note.getTitle().length(); i++) out.append('=');
        out.append("\n\n");
        String body = note.getContent() == null ? "" : note.getContent();
        for (String line : body.split("\n", -1)) {
            out.append(Md.plainLine(line, 0, line.length())).append('\n');
        }
        NoteFile.writeAtomic(file, out.toString());
        return file;
    }

    /** ZIPs the given notes, keeping their folder structure. */
    private File zipNotes(List<Note> notes, String archiveName) throws IOException {
        return zipNotes(notes, java.util.Collections.<CanvasStore.Entry>emptyList(), archiveName);
    }

    /** ZIPs notes and canvases, keeping their vault-relative folder structure. */
    private File zipNotes(List<Note> notes, List<CanvasStore.Entry> canvases,
                          String archiveName) throws IOException {
        File target = new File(vault.exportCacheDir(), archiveName + ".zip");
        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(new FileOutputStream(target));
            List<String> attachments = new ArrayList<>();
            for (Note note : notes) {
                File source = vault.resolve(note.getId());
                if (!source.exists()) continue;
                addFile(zip, source, note.getId());
                String body = NoteFile.readText(source);
                for (String ref : Md.attachmentRefs(body)) {
                    if (!attachments.contains(ref)) attachments.add(ref);
                }
            }
            addCanvases(zip, canvases);
            for (String ref : attachments) {
                File file = repository.resolveAttachment(ref);
                if (file != null && file.exists()) {
                    addFile(zip, file, "attachments/" + file.getName());
                }
            }
        } finally {
            closeQuietly(zip);
        }
        return target;
    }

    private File zipVault(List<Note> notes, List<CanvasStore.Entry> canvases) throws IOException {
        File target = new File(vault.exportCacheDir(), "shard-vault-backup.zip");
        return zipVault(notes, canvases, target);
    }

    private File zipVault(List<Note> notes, List<CanvasStore.Entry> canvases, File target)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create backup directory");
        }
        File partial = new File(parent, target.getName() + ".partial");
        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(new FileOutputStream(partial));
            for (Note note : notes) {
                File source = vault.resolve(note.getId());
                if (source.exists()) addFile(zip, source, note.getId());
            }
            addCanvases(zip, canvases);
            File[] attachments = vault.attachmentsDir().listFiles();
            if (attachments != null) {
                for (File file : attachments) {
                    if (file.isFile()) addFile(zip, file, "attachments/" + file.getName());
                }
            }
        } finally {
            closeQuietly(zip);
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("cannot replace previous backup");
        }
        if (!partial.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("cannot finalise backup");
        }
        return target;
    }

    private static File zipEnvironment(File root, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create backup directory");
        }
        File partial = new File(parent, target.getName() + ".partial");
        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(new FileOutputStream(partial));
            addTree(zip, root, root);
        } catch (Throwable error) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("cannot create environment backup", error);
        } finally {
            closeQuietly(zip);
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("cannot replace previous backup");
        }
        if (!partial.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("cannot finalise backup");
        }
        return target;
    }

    private static void addTree(ZipOutputStream zip, File root, File current)
            throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        java.util.Arrays.sort(children,
                (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (child.getName().endsWith(".partial") || child.getName().endsWith(".tmp")) {
                continue;
            }
            String entry = root.toURI().relativize(child.toURI()).getPath();
            if (entry.isEmpty() || entry.startsWith("../")) continue;
            if (child.isDirectory()) {
                ZipEntry directory = new ZipEntry(entry.endsWith("/") ? entry : entry + "/");
                directory.setTime(child.lastModified());
                zip.putNextEntry(directory);
                zip.closeEntry();
                addTree(zip, root, child);
            } else if (child.isFile()) {
                addFile(zip, child, entry);
            }
        }
    }

    /** Test seam for verifying that a complete environment remains recoverable. */
    static File zipEnvironmentForTest(File root, File target) throws IOException {
        return zipEnvironment(root, target);
    }

    private void addCanvases(ZipOutputStream zip, List<CanvasStore.Entry> canvases)
            throws IOException {
        for (CanvasStore.Entry entry : canvases) {
            File source = vault.resolve(entry.id);
            if (source.isFile()) addFile(zip, source, entry.id);
        }
    }

    private static String canvasFolder(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? "" : id.substring(0, slash);
    }

    private static void addFile(ZipOutputStream zip, File source, String entryName)
            throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        FileInputStream in = new FileInputStream(source);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
        } finally {
            closeQuietly(in);
        }
        zip.closeEntry();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) { }
    }

    private byte[] readFileBytes(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return data;
        } finally {
            closeQuietly(in);
        }
    }

    private String readFileString(File file) {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file, e);
        } finally {
            closeQuietly(reader);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- PDF

    private File renderPdf(Note note) {
        try {
            File pdfFile = new File(vault.exportCacheDir(), Md.safeFileName(note.getTitle()) + ".pdf");
            PdfDocument document = new PdfDocument();

            PdfMarkdownRenderer renderer = new PdfMarkdownRenderer(document);
            renderer.render(note);
            renderer.finish();

            java.io.FileOutputStream fos = new java.io.FileOutputStream(pdfFile);
            document.writeTo(fos);
            fos.close();
            document.close();

            return pdfFile;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    static SpannableStringBuilder replaceStructuralMarker(CharSequence text, char marker,
                                                           String replacement) {
        SpannableStringBuilder replaced = new SpannableStringBuilder(text);
        TexPreview.LongtableRepeatHeaderSpan[] repeatHeaders = replaced.getSpans(
                0, replaced.length(), TexPreview.LongtableRepeatHeaderSpan.class);
        for (TexPreview.LongtableRepeatHeaderSpan repeatHeader : repeatHeaders) {
            int start = replaced.getSpanStart(repeatHeader);
            int end = replaced.getSpanEnd(repeatHeader);
            int flags = replaced.getSpanFlags(repeatHeader);
            CharSequence resolvedHeader = replaceStructuralMarker(
                    repeatHeader.getHeader(), marker, replacement);
            replaced.removeSpan(repeatHeader);
            replaced.setSpan(repeatHeader.withHeader(resolvedHeader), start, end, flags);
        }

        int position = indexOfStructuralMarker(replaced, marker, 0);
        while (position >= 0) {
            ReplacementSpan[] spans = replaced.getSpans(
                    position, position + 1, ReplacementSpan.class);
            for (ReplacementSpan span : spans) replaced.removeSpan(span);
            replaced.replace(position, position + 1, replacement);
            position = indexOfStructuralMarker(
                    replaced, marker, position + replacement.length());
        }
        return replaced;
    }

    private static int indexOfStructuralMarker(CharSequence text, char target, int from) {
        for (int i = Math.max(0, from); i < text.length(); i++) {
            if (text.charAt(i) == target) return i;
        }
        return -1;
    }

    private final class PdfMarkdownRenderer {
        private static final int PAGE_WIDTH = 595;
        private static final int PAGE_HEIGHT = 842;
        private static final float MARGIN_X = 48f;
        private static final float MARGIN_TOP = 56f;
        private static final float MARGIN_BOTTOM = 56f;
        private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2f);
        private static final float CODE_PADDING_X = 12f;
        private static final float CODE_PADDING_Y = 10f;

        private final PdfDocument document;
        private final TextPaint titlePaint = createPaint(34f, 0xFF242529, Typeface.BOLD, false);
        private final TextPaint bodyPaint = createPaint(13.5f, 0xFF3F3F3F, Typeface.NORMAL, false);
        private final TextPaint quotePaint = createPaint(13.5f, 0xFF555555, Typeface.ITALIC, false);
        private final TextPaint codePaint = createPaint(11.5f, HighlightTheme.NOTION_LIGHT.operator, Typeface.NORMAL, true);
        private final Paint dividerPaint = createFillPaint(0xFFE1E1E1);
        private final Paint codeBgPaint = createFillPaint(HighlightTheme.NOTION_LIGHT.background);
        private final Paint quoteBgPaint = createFillPaint(0xFFF8F8F7);
        private final Paint quoteBarPaint = createFillPaint(0xFFD0D0CD);
        private final Paint tableBorderPaint = createStrokePaint(0xFFE0E0DE, 1f);

        private PdfDocument.Page page;
        private Canvas canvas;
        private float y;
        private int currentPageWidth = PAGE_WIDTH;
        private int currentPageHeight = PAGE_HEIGHT;
        private boolean currentLandscape;

        PdfMarkdownRenderer(PdfDocument document) {
            this.document = document;
        }

        void render(Note note) {
            if (NoteFile.isTexFile(note.getId())) {
                TexPreview.Palette palette = new TexPreview.Palette();
                palette.text = 0xFF111111;
                palette.muted = 0xFF666666;
                palette.accent = 0xFF0B57D0;
                palette.mathBackground = 0x0A000000;
                TexPreview texPreview = new TexPreview(palette);
                CharSequence rendered = texPreview.render(note.getContent());
                drawStyledText(rendered);
            } else {
                drawTitle(note.getDisplayName());
                drawMarkdown(note.getContent());
            }
        }

        private void drawStyledText(CharSequence text) {
            if (text == null || text.length() == 0) return;
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            // The TeX source uses extarticle[14pt]; keep the PDF body at the
            // same size so custom spans do not appear disproportionately small.
            paint.setTextSize(14.0f);
            paint.setColor(0xFF111111);
            paint.setTypeface(Typeface.SERIF);

            CharSequence resolved = resolveLastPageReferences(text, paint);
            List<StyledSegment> segments = splitStyledSegments(resolved);
            boolean drewSegment = false;
            for (StyledSegment segment : segments) {
                if (!hasRenderableText(segment.text)) continue;
                if (!drewSegment) {
                    if (page == null) startPage(segment.landscape);
                } else {
                    newPage(segment.landscape);
                }
                drawStyledSegment(segment.text, paint);
                drewSegment = true;
            }
            if (!drewSegment && page == null) startPage(false);
        }

        private void drawStyledSegment(CharSequence text, TextPaint paint) {
            float contentWidth = currentPageWidth - (MARGIN_X * 2f);
            StaticLayout layout = new StaticLayout(text, paint, (int) contentWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.5f, 0f, false);
            List<RepeatHeader> repeatHeaders = findRepeatHeaders(
                    text, layout, paint, (int) contentWidth);

            float pageContentHeight = currentPageHeight - MARGIN_TOP - MARGIN_BOTTOM;
            int lineCount = layout.getLineCount();
            int currentLine = 0;

            while (currentLine < lineCount) {
                int lineTop = layout.getLineTop(currentLine);
                RepeatHeader repeatHeader = repeatHeaderForLine(repeatHeaders, currentLine);
                if (currentLine == 0 && repeatHeader != null
                        && !repeatHeader.repeatOnFirstPage) {
                    repeatHeader = null;
                }
                float repeatedHeaderHeight = repeatHeader == null
                        ? 0f : repeatHeader.layout.getHeight() + 6f;
                float availableHeight = pageContentHeight - repeatedHeaderHeight;
                int nextLine = currentLine;
                while (nextLine < lineCount
                        && (layout.getLineBottom(nextLine) - lineTop) <= availableHeight) {
                    nextLine++;
                }
                if (nextLine == currentLine) nextLine = currentLine + 1;

                if (repeatHeader != null) {
                    canvas.save();
                    canvas.translate(MARGIN_X, MARGIN_TOP);
                    repeatHeader.layout.draw(canvas);
                    canvas.restore();
                }
                canvas.save();
                canvas.translate(MARGIN_X, MARGIN_TOP + repeatedHeaderHeight - lineTop);
                canvas.clipRect(0, lineTop, contentWidth, layout.getLineBottom(nextLine - 1));
                layout.draw(canvas);
                canvas.restore();

                currentLine = nextLine;
                if (currentLine < lineCount) {
                    newPage();
                }
            }
        }

        private CharSequence resolveLastPageReferences(CharSequence text, TextPaint paint) {
            int guess = 1;
            SpannableStringBuilder resolved = null;
            for (int i = 0; i < 4; i++) {
                resolved = replaceStructuralMarker(
                        text, TexPreview.LAST_PAGE_MARKER, String.valueOf(guess));
                int measured = countStyledPages(resolved, paint);
                if (measured == guess) break;
                guess = measured;
            }
            return resolved == null ? text : resolved;
        }

        private int countStyledPages(CharSequence text, TextPaint paint) {
            int pages = 0;
            for (StyledSegment segment : splitStyledSegments(text)) {
                if (hasRenderableText(segment.text)) {
                    int pageWidth = segment.landscape ? PAGE_HEIGHT : PAGE_WIDTH;
                    int pageHeight = segment.landscape ? PAGE_WIDTH : PAGE_HEIGHT;
                    float contentWidth = pageWidth - (MARGIN_X * 2f);
                    StaticLayout layout = new StaticLayout(segment.text, paint, (int) contentWidth,
                            Layout.Alignment.ALIGN_NORMAL, 1.5f, 0f, false);
                    pages += pageCount(segment.text, layout, pageHeight, paint,
                            (int) contentWidth);
                }
            }
            return Math.max(1, pages);
        }

        private int pageCount(CharSequence text, StaticLayout layout, int pageHeight,
                              TextPaint paint, int contentWidth) {
            float pageContentHeight = pageHeight - MARGIN_TOP - MARGIN_BOTTOM;
            List<RepeatHeader> repeatHeaders = findRepeatHeaders(
                    text, layout, paint, contentWidth);
            int pages = 0;
            int currentLine = 0;
            while (currentLine < layout.getLineCount()) {
                int lineTop = layout.getLineTop(currentLine);
                RepeatHeader repeatHeader = repeatHeaderForLine(repeatHeaders, currentLine);
                if (currentLine == 0 && repeatHeader != null
                        && !repeatHeader.repeatOnFirstPage) {
                    repeatHeader = null;
                }
                float repeatedHeaderHeight = repeatHeader == null
                        ? 0f : repeatHeader.layout.getHeight() + 6f;
                float availableHeight = pageContentHeight - repeatedHeaderHeight;
                int nextLine = currentLine;
                while (nextLine < layout.getLineCount()
                        && (layout.getLineBottom(nextLine) - lineTop) <= availableHeight) {
                    nextLine++;
                }
                currentLine = nextLine == currentLine ? currentLine + 1 : nextLine;
                pages++;
            }
            return Math.max(1, pages);
        }

        private List<RepeatHeader> findRepeatHeaders(CharSequence text, StaticLayout layout,
                                                     TextPaint paint, int contentWidth) {
            List<RepeatHeader> headers = new ArrayList<>();
            int searchFrom = 0;
            while (true) {
                int start = indexOf(text, TexPreview.LONGTABLE_HEADER_START_MARKER,
                        searchFrom);
                if (start < 0) break;
                int end = indexOf(text, TexPreview.LONGTABLE_HEADER_END_MARKER, start + 1);
                if (end < 0) break;
                int tableEnd = indexOf(text, TexPreview.LONGTABLE_END_MARKER, end + 1);
                if (tableEnd < 0) break;
                CharSequence headerText = text.subSequence(start + 1, end);
                if (hasRenderableText(headerText)) {
                    StaticLayout headerLayout = new StaticLayout(headerText, paint, contentWidth,
                            Layout.Alignment.ALIGN_NORMAL, 1.35f, 0f, false);
                    int firstDataLine = layout.getLineForOffset(
                            Math.min(end + 1, text.length()));
                    int tableEndLine = layout.getLineForOffset(
                            Math.min(tableEnd, text.length()));
                    headers.add(new RepeatHeader(
                            headerLayout, firstDataLine, tableEndLine, false));
                }
                searchFrom = tableEnd + 1;
            }
            if (text instanceof Spanned) {
                Spanned spanned = (Spanned) text;
                searchFrom = 0;
                while (true) {
                    int marker = indexOf(text, TexPreview.LONGTABLE_REPEAT_HEADER_MARKER,
                            searchFrom);
                    if (marker < 0) break;
                    int tableEnd = indexOf(text, TexPreview.LONGTABLE_END_MARKER,
                            marker + 1);
                    if (tableEnd < 0) break;
                    TexPreview.LongtableRepeatHeaderSpan[] spans = spanned.getSpans(
                            marker, marker + 1, TexPreview.LongtableRepeatHeaderSpan.class);
                    if (spans.length > 0) {
                        CharSequence headerText = spans[0].getHeader();
                        if (hasRenderableText(headerText)) {
                            StaticLayout headerLayout = new StaticLayout(headerText, paint,
                                    contentWidth, Layout.Alignment.ALIGN_NORMAL,
                                    1.35f, 0f, false);
                            int firstDataLine = layout.getLineForOffset(
                                    Math.min(marker + 1, text.length()));
                            int tableEndLine = layout.getLineForOffset(
                                    Math.min(tableEnd, text.length()));
                            headers.add(new RepeatHeader(headerLayout, firstDataLine,
                                    tableEndLine, spans[0].repeatsOnFirstPage()));
                        }
                    }
                    searchFrom = tableEnd + 1;
                }
            }
            return headers;
        }

        private RepeatHeader repeatHeaderForLine(List<RepeatHeader> headers, int line) {
            for (RepeatHeader header : headers) {
                if (line >= header.firstDataLine && line < header.tableEndLine) return header;
            }
            return null;
        }

        private final class RepeatHeader {
            final StaticLayout layout;
            final int firstDataLine;
            final int tableEndLine;
            final boolean repeatOnFirstPage;

            RepeatHeader(StaticLayout layout, int firstDataLine, int tableEndLine,
                         boolean repeatOnFirstPage) {
                this.layout = layout;
                this.firstDataLine = firstDataLine;
                this.tableEndLine = tableEndLine;
                this.repeatOnFirstPage = repeatOnFirstPage;
            }
        }

        private List<StyledSegment> splitStyledSegments(CharSequence text) {
            List<StyledSegment> segments = new ArrayList<>();
            SpannableStringBuilder current = new SpannableStringBuilder();
            boolean landscape = false;
            int copiedFrom = 0;
            for (int i = 0; i < text.length(); i++) {
                char marker = text.charAt(i);
                if (marker != TexPreview.PAGE_BREAK_MARKER
                        && marker != TexPreview.LANDSCAPE_START_MARKER
                        && marker != TexPreview.LANDSCAPE_END_MARKER) {
                    continue;
                }
                if (i > copiedFrom) current.append(text, copiedFrom, i);
                if (hasRenderableText(current)) {
                    addStyledSegment(segments, current, landscape);
                    current = new SpannableStringBuilder();
                }
                if (marker == TexPreview.LANDSCAPE_START_MARKER) landscape = true;
                else if (marker == TexPreview.LANDSCAPE_END_MARKER) landscape = false;
                copiedFrom = i + 1;
            }
            if (copiedFrom < text.length()) current.append(text, copiedFrom, text.length());
            if (hasRenderableText(current)) addStyledSegment(segments, current, landscape);
            return segments;
        }

        private void addStyledSegment(List<StyledSegment> segments,
                                      SpannableStringBuilder text, boolean landscape) {
            int start = 0;
            int end = text.length();
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            if (start < end) segments.add(new StyledSegment(text.subSequence(start, end), landscape));
        }

        private final class StyledSegment {
            final CharSequence text;
            final boolean landscape;

            StyledSegment(CharSequence text, boolean landscape) {
                this.text = text;
                this.landscape = landscape;
            }
        }

        private int indexOf(CharSequence text, char target, int from) {
            for (int i = Math.max(0, from); i < text.length(); i++) {
                if (text.charAt(i) == target) return i;
            }
            return -1;
        }

        private boolean hasRenderableText(CharSequence text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!Character.isWhitespace(c) && !isInvisibleStructuralMarker(c)) return true;
            }
            return false;
        }

        private boolean isInvisibleStructuralMarker(char c) {
            return c == TexPreview.PAGE_BREAK_MARKER
                    || c == TexPreview.LANDSCAPE_START_MARKER
                    || c == TexPreview.LANDSCAPE_END_MARKER
                    || c == TexPreview.LONGTABLE_HEADER_START_MARKER
                    || c == TexPreview.LONGTABLE_HEADER_END_MARKER
                    || c == TexPreview.LONGTABLE_END_MARKER
                    || c == TexPreview.LONGTABLE_REPEAT_HEADER_MARKER;
        }

        void finish() {
            if (page == null) startPage(false);
            if (page != null) {
                document.finishPage(page);
                page = null;
            }
        }

        private void startPage(boolean landscape) {
            currentLandscape = landscape;
            currentPageWidth = landscape ? PAGE_HEIGHT : PAGE_WIDTH;
            currentPageHeight = landscape ? PAGE_WIDTH : PAGE_HEIGHT;
            int pageNumber = document.getPages().size() + 1;
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                currentPageWidth,
                currentPageHeight,
                pageNumber
            ).create();
            page = document.startPage(pageInfo);
            canvas = page.getCanvas();
            y = MARGIN_TOP;
            if (pageNumber > 1) {
                Paint pageNumberPaint = createFillPaint(0xFF555555);
                pageNumberPaint.setTextSize(10f);
                pageNumberPaint.setTypeface(Typeface.SERIF);
                pageNumberPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(String.valueOf(pageNumber), currentPageWidth - MARGIN_X, 34f,
                        pageNumberPaint);
            }
        }

        private void newPage() {
            newPage(currentLandscape);
        }

        private void newPage(boolean landscape) {
            if (page != null) document.finishPage(page);
            startPage(landscape);
        }

        private void ensureSpace(float height) {
            if (page == null) startPage(false);
            if (y + height > PAGE_HEIGHT - MARGIN_BOTTOM) {
                newPage();
            }
        }

        private void drawTitle(String title) {
            StaticLayout layout = buildLayout(title, titlePaint, CONTENT_WIDTH);
            ensureSpace(layout.getHeight() + 26f);
            drawLayout(layout, MARGIN_X, y);
            y += layout.getHeight() + 22f;
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 1.2f, dividerPaint);
            y += 28f;
        }

        private void drawMarkdown(String content) {
            String[] lines = content.split("\\r?\\n", -1);
            boolean inCodeBlock = false;
            String codeLanguage = "";
            StringBuilder code = new StringBuilder();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("```")) {
                    if (inCodeBlock) {
                        drawCodeBlock(codeLanguage, stripTrailingNewline(code.toString()));
                        code.setLength(0);
                        codeLanguage = "";
                        inCodeBlock = false;
                    } else {
                        codeLanguage = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                        inCodeBlock = true;
                    }
                    continue;
                }

                if (inCodeBlock) {
                    code.append(line).append('\n');
                    continue;
                }

                drawMarkdownLine(line);
            }

            if (inCodeBlock) {
                drawCodeBlock(codeLanguage, stripTrailingNewline(code.toString()));
            }
        }

        private void drawMarkdownLine(String line) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                addSpacing(10f);
                return;
            }

            Matcher heading = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(trimmed);
            if (heading.matches()) {
                drawHeading(heading.group(2), heading.group(1).length());
                return;
            }

            Matcher image = IMAGE_PATTERN.matcher(trimmed);
            if (image.matches() || (trimmed.startsWith("![") && image.find())) {
                drawImage(image.group(2), image.group(1));
                return;
            }

            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                drawRule();
                return;
            }

            if (trimmed.startsWith("> ")) {
                drawQuote(formatInlineMarkdown(trimmed.substring(2)));
                return;
            }

            Matcher task = Pattern.compile("^[-*+]\\s+\\[([ xX])\\]\\s+(.+)$").matcher(trimmed);
            if (task.matches()) {
                boolean checked = task.group(1).equalsIgnoreCase("x");
                drawListItem(formatInlineMarkdown((checked ? "\u2611 " : "\u2610 ") + task.group(2)), false);
                return;
            }

            Matcher bullet = Pattern.compile("^[-*+]\\s+(.+)$").matcher(trimmed);
            if (bullet.matches()) {
                drawListItem(formatInlineMarkdown(bullet.group(1)), true);
                return;
            }

            Matcher ordered = Pattern.compile("^(\\d+)\\.\\s+(.+)$").matcher(trimmed);
            if (ordered.matches()) {
                drawListItem(formatInlineMarkdown(ordered.group(1) + ". " + ordered.group(2)), false);
                return;
            }

            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                drawTableRow(trimmed);
                return;
            }

            drawText(formatInlineMarkdown(line), bodyPaint, MARGIN_X, CONTENT_WIDTH, 7f);
        }

        private void drawHeading(String text, int level) {
            float size = level == 1 ? 24f : level == 2 ? 19f : level == 3 ? 16f : 14f;
            TextPaint paint = createPaint(size, 0xFF242529, Typeface.BOLD, false);
            addSpacing(level <= 2 ? 8f : 5f);
            drawText(formatInlineMarkdown(text), paint, MARGIN_X, CONTENT_WIDTH, level == 1 ? 12f : 9f);
        }

        private void drawText(CharSequence text, TextPaint paint, float x, float width, float bottomSpacing) {
            if (text == null || text.length() == 0) return;
            StaticLayout layout = buildLayout(text, paint, width);
            ensureSpace(layout.getHeight() + bottomSpacing);
            drawLayout(layout, x, y);
            y += layout.getHeight() + bottomSpacing;
        }

        private void drawQuote(CharSequence text) {
            StaticLayout layout = buildLayout(text, quotePaint, CONTENT_WIDTH - 28f);
            float height = layout.getHeight() + 16f;
            ensureSpace(height + 8f);
            canvas.drawRoundRect(new RectF(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + height), 6f, 6f, quoteBgPaint);
            canvas.drawRect(MARGIN_X, y, MARGIN_X + 4f, y + height, quoteBarPaint);
            drawLayout(layout, MARGIN_X + 16f, y + 8f);
            y += height + 8f;
        }

        private void drawListItem(CharSequence text, boolean bullet) {
            CharSequence content = text;
            if (bullet) {
                SpannableStringBuilder prefixed = new SpannableStringBuilder("\u2022 ");
                prefixed.append(text);
                content = prefixed;
            }
            drawText(content, bodyPaint, MARGIN_X + 10f, CONTENT_WIDTH - 10f, 4f);
        }

        private void drawRule() {
            ensureSpace(22f);
            y += 8f;
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 1f, dividerPaint);
            y += 14f;
        }

        private void drawTableRow(String row) {
            if (row.matches("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")) {
                return;
            }

            String normalized = row;
            if (normalized.startsWith("|")) normalized = normalized.substring(1);
            if (normalized.endsWith("|")) normalized = normalized.substring(0, normalized.length() - 1);
            String[] cells = normalized.split("\\|");
            if (cells.length == 0) return;

            float cellWidth = CONTENT_WIDTH / cells.length;
            List<StaticLayout> layouts = new ArrayList<>();
            float rowHeight = 0f;
            for (String cell : cells) {
                StaticLayout layout = buildLayout(formatInlineMarkdown(cell.trim()), bodyPaint, cellWidth - 14f);
                layouts.add(layout);
                rowHeight = Math.max(rowHeight, layout.getHeight() + 14f);
            }

            ensureSpace(rowHeight);
            float x = MARGIN_X;
            for (StaticLayout layout : layouts) {
                canvas.drawRect(x, y, x + cellWidth, y + rowHeight, tableBorderPaint);
                drawLayout(layout, x + 7f, y + 7f);
                x += cellWidth;
            }
            y += rowHeight;
        }

        private void drawImage(String imageRef, String altText) {
            if (imageRef == null || imageRef.startsWith("http://") || imageRef.startsWith("https://")) {
                drawText(altText != null && !altText.isEmpty() ? altText : imageRef, bodyPaint, MARGIN_X, CONTENT_WIDTH, 7f);
                return;
            }

            File imgFile = repository.resolveAttachment(imageRef);
            if (imgFile == null || !imgFile.exists()) {
                drawText(altText != null && !altText.isEmpty() ? altText : imageRef, bodyPaint, MARGIN_X, CONTENT_WIDTH, 7f);
                return;
            }

            Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
            if (bmp == null) return;
            float maxDrawableHeight = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;
            float scale = Math.min(1f, Math.min(CONTENT_WIDTH / bmp.getWidth(), maxDrawableHeight / bmp.getHeight()));
            float drawWidth = bmp.getWidth() * scale;
            float drawHeight = bmp.getHeight() * scale;
            ensureSpace(drawHeight + 12f);
            canvas.drawBitmap(bmp, null, new RectF(MARGIN_X, y, MARGIN_X + drawWidth, y + drawHeight), null);
            y += drawHeight + 12f;
            bmp.recycle();
        }

        private void drawCodeBlock(String language, String code) {
            String safeCode = code == null || code.isEmpty() ? " " : code;
            String[] lines = safeCode.split("\\n", -1);
            StringBuilder chunk = new StringBuilder();

            for (String line : lines) {
                String candidate = chunk.length() == 0 ? line : chunk + "\n" + line;
                StaticLayout candidateLayout = buildCodeLayout(language, candidate);
                float candidateHeight = candidateLayout.getHeight() + (CODE_PADDING_Y * 2f);

                if (chunk.length() > 0 && y + candidateHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                    drawCodeChunk(language, chunk.toString());
                    chunk.setLength(0);
                    chunk.append(line);
                } else {
                    chunk.setLength(0);
                    chunk.append(candidate);
                }
            }

            if (chunk.length() > 0) {
                drawCodeChunk(language, chunk.toString());
            }
        }

        private void drawCodeChunk(String language, String code) {
            StaticLayout layout = buildCodeLayout(language, code);
            float blockHeight = layout.getHeight() + (CODE_PADDING_Y * 2f);
            ensureSpace(blockHeight + 10f);
            RectF rect = new RectF(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + blockHeight);
            canvas.drawRoundRect(rect, 6f, 6f, codeBgPaint);
            drawLayout(layout, MARGIN_X + CODE_PADDING_X, y + CODE_PADDING_Y);
            y += blockHeight + 10f;
        }

        private StaticLayout buildCodeLayout(String language, String code) {
            CharSequence highlighted = highlightCode(language, code);
            return buildLayout(highlighted, codePaint, CONTENT_WIDTH - (CODE_PADDING_X * 2f));
        }

        private CharSequence highlightCode(String language, String code) {
            Highlighter highlighter = LanguageDetector.forLanguage(language);
            if (highlighter == null) {
                highlighter = LanguageDetector.forLanguage(LanguageDetector.detectFromContent(code));
            }
            if (highlighter == null) {
                return new SpannableStringBuilder(code);
            }
            highlighter.setTheme(HighlightTheme.NOTION_LIGHT);
            return highlighter.highlight(code);
        }

        private StaticLayout buildLayout(CharSequence text, TextPaint paint, float width) {
            int layoutWidth = Math.max(1, (int) width);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                return StaticLayout.Builder.obtain(text, 0, text.length(), paint, layoutWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(2f, 1.0f)
                        .setIncludePad(false)
                        .build();
            }
            //noinspection deprecation
            return new StaticLayout(text, paint, layoutWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.0f, 2f, false);
        }

        private void drawLayout(StaticLayout layout, float x, float top) {
            canvas.save();
            canvas.translate(x, top);
            layout.draw(canvas);
            canvas.restore();
        }

        private void addSpacing(float amount) {
            y += amount;
            if (y > PAGE_HEIGHT - MARGIN_BOTTOM) {
                newPage();
            }
        }

        private TextPaint createPaint(float textSize, int color, int style, boolean monospace) {
            TextPaint paint = new TextPaint();
            paint.setAntiAlias(true);
            paint.setTextSize(textSize);
            paint.setColor(color);
            Typeface base = monospace ? Typeface.MONOSPACE : Typeface.DEFAULT;
            paint.setTypeface(Typeface.create(base, style));
            return paint;
        }

        private Paint createFillPaint(int color) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            return paint;
        }

        private Paint createStrokePaint(int color, float width) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            return paint;
        }

        private String stripTrailingNewline(String value) {
            if (value.endsWith("\n")) {
                return value.substring(0, value.length() - 1);
            }
            return value;
        }

        private CharSequence formatInlineMarkdown(String text) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            appendInlineMarkdown(out, text, 0, text.length());
            return out;
        }

        private void appendInlineMarkdown(SpannableStringBuilder out, String text, int start, int end) {
            int i = start;
            while (i < end) {
                if (text.startsWith("![", i)) {
                    int labelEnd = text.indexOf("](", i + 2);
                    int destinationEnd = labelEnd >= 0 ? text.indexOf(')', labelEnd + 2) : -1;
                    if (labelEnd > i + 2 && destinationEnd > labelEnd) {
                        out.append(text, i + 2, labelEnd);
                        i = destinationEnd + 1;
                        continue;
                    }
                }

                if (text.startsWith("[[", i)) {
                    int close = text.indexOf("]]", i + 2);
                    if (close > i + 2) {
                        String linkText = text.substring(i + 2, close);
                        int pipe = linkText.indexOf('|');
                        String display = pipe >= 0 ? linkText.substring(pipe + 1) : linkText;
                        int spanStart = out.length();
                        out.append(display);
                        setSpan(out, new ForegroundColorSpan(0xFF0B6E99), spanStart, out.length());
                        setSpan(out, new UnderlineSpan(), spanStart, out.length());
                        i = close + 2;
                        continue;
                    }
                }

                if (text.startsWith("[", i)) {
                    int labelEnd = text.indexOf("](", i + 1);
                    int destinationEnd = labelEnd >= 0 ? text.indexOf(')', labelEnd + 2) : -1;
                    if (labelEnd > i + 1 && destinationEnd > labelEnd) {
                        int spanStart = out.length();
                        appendInlineMarkdown(out, text, i + 1, labelEnd);
                        setSpan(out, new ForegroundColorSpan(0xFF0B6E99), spanStart, out.length());
                        setSpan(out, new UnderlineSpan(), spanStart, out.length());
                        i = destinationEnd + 1;
                        continue;
                    }
                }

                if (text.charAt(i) == '`') {
                    int close = text.indexOf('`', i + 1);
                    if (close > i + 1) {
                        int spanStart = out.length();
                        out.append(text, i + 1, close);
                        setSpan(out, new TypefaceSpan("monospace"), spanStart, out.length());
                        setSpan(out, new BackgroundColorSpan(0xFFEDECE9), spanStart, out.length());
                        setSpan(out, new ForegroundColorSpan(0xFF37352F), spanStart, out.length());
                        i = close + 1;
                        continue;
                    }
                }

                if (text.startsWith("~~", i)) {
                    int close = text.indexOf("~~", i + 2);
                    if (close > i + 2) {
                        int spanStart = out.length();
                        appendInlineMarkdown(out, text, i + 2, close);
                        setSpan(out, new StrikethroughSpan(), spanStart, out.length());
                        i = close + 2;
                        continue;
                    }
                }

                if (text.startsWith("**", i) || text.startsWith("__", i)) {
                    String marker = text.substring(i, i + 2);
                    int close = text.indexOf(marker, i + 2);
                    if (close > i + 2) {
                        int spanStart = out.length();
                        appendInlineMarkdown(out, text, i + 2, close);
                        setSpan(out, new StyleSpan(Typeface.BOLD), spanStart, out.length());
                        i = close + 2;
                        continue;
                    }
                }

                char marker = text.charAt(i);
                if ((marker == '*' || marker == '_') && canOpenSingleEmphasis(text, i, end)) {
                    int close = findSingleEmphasisClose(text, marker, i + 1, end);
                    if (close > i + 1) {
                        int spanStart = out.length();
                        appendInlineMarkdown(out, text, i + 1, close);
                        setSpan(out, new StyleSpan(Typeface.ITALIC), spanStart, out.length());
                        i = close + 1;
                        continue;
                    }
                }

                out.append(text.charAt(i));
                i++;
            }
        }

        private boolean canOpenSingleEmphasis(String text, int index, int end) {
            if (index + 1 >= end || Character.isWhitespace(text.charAt(index + 1))) {
                return false;
            }
            if (text.charAt(index) == '_' && index > 0 && Character.isLetterOrDigit(text.charAt(index - 1))) {
                return false;
            }
            return true;
        }

        private int findSingleEmphasisClose(String text, char marker, int from, int end) {
            int close = text.indexOf(marker, from);
            while (close > from && close < end) {
                boolean closesWordUnderscore = marker == '_' &&
                    close + 1 < end &&
                    Character.isLetterOrDigit(text.charAt(close + 1));
                if (!Character.isWhitespace(text.charAt(close - 1)) && !closesWordUnderscore) {
                    return close;
                }
                close = text.indexOf(marker, close + 1);
            }
            return -1;
        }

        private void setSpan(SpannableStringBuilder out, Object span, int start, int end) {
            if (end > start) {
                out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    // ---------------------------------------------------------------- HTML

    private File renderHtml(Note note) {
        try {
            File htmlFile = new File(vault.exportCacheDir(), Md.safeFileName(note.getTitle()) + ".html");
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
            html.append("<meta charset=\"UTF-8\">\n");
            html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
            html.append("<title>").append(escapeHtml(note.getTitle())).append("</title>\n");
            html.append("<style>\n");
            html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;max-width:800px;margin:0 auto;padding:20px;color:#333;line-height:1.6;}\n");
            html.append("h1{color:#212121;border-bottom:1px solid #e0e0e0;padding-bottom:8px;}\n");
            html.append("h2{color:#424242;margin-top:24px;}\n");
            html.append("h3{color:#616161;}\n");
            html.append("code{background:#f5f5f5;padding:2px 6px;border-radius:3px;font-size:0.9em;}\n");
            html.append("pre{background:#1e1e1e;color:#d4d4d4;padding:16px;border-radius:8px;overflow-x:auto;}\n");
            html.append("pre code{background:none;padding:0;color:inherit;}\n");
            html.append("blockquote{border-left:4px solid #6C63FF;margin:16px 0;padding:8px 16px;background:#f8f9fe;border-radius:0 8px 8px 0;}\n");
            html.append("img{max-width:100%;border-radius:8px;margin:8px 0;}\n");
            html.append("a{color:#6C63FF;text-decoration:none;}\n");
            html.append("a:hover{text-decoration:underline;}\n");
            html.append("table{border-collapse:collapse;width:100%;margin:16px 0;}\n");
            html.append("th,td{border:1px solid #e0e0e0;padding:8px 12px;text-align:left;}\n");
            html.append("th{background:#f5f5f5;font-weight:600;}\n");
            html.append("ul,ol{padding-left:24px;}\n");
            html.append("li{margin:4px 0;}\n");
            html.append("hr{border:none;border-top:1px solid #e0e0e0;margin:24px 0;}\n");
            html.append(".meta{color:#757575;font-size:0.9em;margin-bottom:24px;}\n");
            html.append("</style>\n</head>\n<body>\n");

            html.append("<h1>").append(escapeHtml(note.getDisplayName())).append("</h1>\n");
            html.append("<div class=\"meta\">")
                    .append(escapeHtml(formatDate(note.getCreatedMillis())))
                    .append(" &middot; ")
                    .append(escapeHtml(formatDate(note.getModifiedMillis())))
                    .append("</div>\n");

            String content = note.getContent();
            html.append(markdownToHtml(content));

            html.append("\n</body>\n</html>");

            java.io.FileWriter writer = new java.io.FileWriter(htmlFile);
            writer.write(html.toString());
            writer.close();
            return htmlFile;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String markdownToHtml(String md) {
        String[] lines = md.split("\n");
        StringBuilder html = new StringBuilder();
        boolean inCodeBlock = false;
        boolean inList = false;
        boolean inOrderedList = false;
        boolean inTable = false;
        StringBuilder codeContent = new StringBuilder();
        String codeLang = "";

        for (String line : lines) {
            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    html.append("<pre><code>").append(escapeHtml(codeContent.toString().trim())).append("</code></pre>\n");
                    codeContent.setLength(0);
                    inCodeBlock = false;
                } else {
                    closeList(html, inList, inOrderedList);
                    inList = false;
                    inOrderedList = false;
                    codeLang = line.substring(3).trim();
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                codeContent.append(line).append("\n");
                continue;
            }

            if (line.startsWith("| ")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                if (!inTable) {
                    html.append("<table>\n");
                    inTable = true;
                }
                if (line.contains("---")) continue;
                String[] cells = line.substring(2).split(" \\| ");
                html.append("<tr>");
                for (String cell : cells) {
                    html.append("<td>").append(escapeHtml(cell.trim())).append("</td>");
                }
                html.append("</tr>\n");
                continue;
            } else if (inTable) {
                html.append("</table>\n");
                inTable = false;
            }

            if (line.startsWith("# ")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("<h1>").append(formatInline(line.substring(2))).append("</h1>\n");
            } else if (line.startsWith("## ")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("<h2>").append(formatInline(line.substring(3))).append("</h2>\n");
            } else if (line.startsWith("### ")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("<h3>").append(formatInline(line.substring(4))).append("</h3>\n");
            } else if (line.startsWith("#### ")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("<h4>").append(formatInline(line.substring(5))).append("</h4>\n");
            } else if (line.startsWith("> ")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("<blockquote>").append(formatInline(line.substring(2))).append("</blockquote>\n");
            } else if (line.startsWith("- [ ] ") || line.startsWith("- [x] ")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                boolean checked = line.startsWith("- [x] ");
                html.append("<p>").append(checked ? "\u2611 " : "\u2610 ").append(formatInline(line.substring(6))).append("</p>\n");
            } else if (line.startsWith("- ")) {
                if (!inList) {
                    closeList(html, false, inOrderedList);
                    inOrderedList = false;
                    html.append("<ul>\n");
                    inList = true;
                }
                html.append("<li>").append(formatInline(line.substring(2))).append("</li>\n");
            } else if (line.matches("^\\d+\\.\\s.*")) {
                if (!inOrderedList) {
                    closeList(html, inList, false);
                    inList = false;
                    html.append("<ol>\n");
                    inOrderedList = true;
                }
                String itemText = line.replaceFirst("^\\d+\\.\\s", "");
                html.append("<li>").append(formatInline(itemText)).append("</li>\n");
            } else if (line.trim().equals("---")) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("<hr>\n");
            } else if (line.trim().isEmpty()) {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("\n");
            } else {
                closeList(html, inList, inOrderedList);
                inList = false;
                inOrderedList = false;
                html.append("<p>").append(formatInline(line)).append("</p>\n");
            }
        }

        closeList(html, inList, inOrderedList);
        if (inTable) html.append("</table>\n");
        return html.toString();
    }

    private void closeList(StringBuilder html, boolean inUl, boolean inOl) {
        if (inUl) html.append("</ul>\n");
        if (inOl) html.append("</ol>\n");
    }

    private String formatInline(String text) {
        text = escapeHtml(text);
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        text = text.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        text = text.replaceAll("~~([^~]+)~~", "<del>$1</del>");
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");
        text = text.replaceAll("!\\[([^\\]]*)\\]\\(([^)]+)\\)", "<img src=\"$2\" alt=\"$1\">");
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        text = text.replaceAll("\\[\\[([^\\]|]+?)(?:\\|([^\\]]+?))?\\]\\]", "<a href=\"#\">$2</a>");
        return text;
    }

    private static String formatDate(long millis) {
        return new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                .format(new java.util.Date(millis));
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
