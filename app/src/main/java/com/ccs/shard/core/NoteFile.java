package com.ccs.shard.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes a single note file.
 *
 * <p>Conventions, chosen so a Shard vault is a folder of portable Markdown and
 * TeX source files that other tools can edit:
 *
 * <ul>
 *   <li>The <b>file name</b> is the title. There is no {@code title:} key, so a
 *       title can never drift out of sync with its file.</li>
 *   <li>The <b>modified time</b> is the file's mtime, not a stored field. This is
 *       what makes "edited 2 minutes ago" correct even when the file was changed
 *       by another app — and it removes the write-amplification of touching front
 *       matter on every save.</li>
 *   <li>Front matter is only emitted when there is something to store, and
 *       unknown keys are round-tripped untouched.</li>
 * </ul>
 */
public final class NoteFile {

    private NoteFile() {}

    public static final String EXT = ".md";
    public static final String TEX_EXT = ".tex";
    private static final String TEX_META_START = "% !shard-metadata";
    private static final String TEX_META_END = "% !end-shard-metadata";
    private static final int EXCERPT_CHARS = 160;

    // ---------------------------------------------------------------- reading

    public static String readText(File file) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(file), 8192);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.max(256, Math.min(file.length(), 1 << 22)));
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            byte[] bytes = out.toByteArray();
            int offset = 0;
            // Skip a UTF-8 BOM; files copied from Windows editors routinely have one.
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
            return new String(bytes, offset, bytes.length - offset, "UTF-8");
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Parses a note from disk, filling every field including the body.
     *
     * @param relativePath vault-relative id for the note
     */
    public static Note read(File file, String relativePath) throws IOException {
        String raw = readText(file);
        Note note = parse(raw, relativePath, file.lastModified());
        note.setSizeBytes(file.length());
        return note;
    }

    /** Parses raw file text. Exposed separately so importers can reuse it. */
    public static Note parse(String raw, String relativePath, long modifiedMillis) {
        Note note = new Note();
        note.setId(relativePath);
        note.setTitle(titleFromPath(relativePath));
        note.setModifiedMillis(modifiedMillis);
        note.setCreatedMillisSilently(modifiedMillis);

        String body = raw;
        if (isTexFile(relativePath) && raw.startsWith(TEX_META_START)) {
            int firstBreak = raw.indexOf('\n');
            if (firstBreak > 0) {
                int end = raw.indexOf("\n" + TEX_META_END, firstBreak);
                if (end >= 0) {
                    String commented = raw.substring(firstBreak + 1, end);
                    StringBuilder metadata = new StringBuilder(commented.length());
                    for (String line : commented.split("\n", -1)) {
                        if (line.startsWith("% ")) line = line.substring(2);
                        else if (line.startsWith("%")) line = line.substring(1);
                        metadata.append(line).append('\n');
                    }
                    applyFrontMatter(note, metadata.toString());
                    int bodyStart = end + 1 + TEX_META_END.length();
                    if (bodyStart < raw.length() && raw.charAt(bodyStart) == '\n') bodyStart++;
                    body = raw.substring(bodyStart);
                }
            }
        } else if (!isTexFile(relativePath) && raw.startsWith("---")) {
            int firstBreak = raw.indexOf('\n');
            if (firstBreak > 0 && raw.substring(0, firstBreak).trim().equals("---")) {
                int end = findFrontMatterEnd(raw, firstBreak + 1);
                if (end > 0) {
                    applyFrontMatter(note, raw.substring(firstBreak + 1, end));
                    int bodyStart = raw.indexOf('\n', end);
                    body = bodyStart < 0 ? "" : raw.substring(bodyStart + 1);
                    if (body.startsWith("\n")) body = body.substring(1);
                }
            }
        }

        note.setContent(body);
        indexBody(note, body);
        return note;
    }

    /** Fills tags, links, excerpt and word count from a body. */
    public static void indexBody(Note note, String body) {
        note.setExcerpt(Md.excerpt(skipTitleHeading(body, note.getTitle()), EXCERPT_CHARS));
        note.setWordCount(Md.wordCount(body));
        note.setOutgoingLinks(Md.wikiLinkKeys(body));
        for (String tag : Md.inlineTags(body)) note.addTag(tag);
        note.setBlockTypeMask(detectBlockTypes(body, isTexFile(note.getId())));
    }

    private static int detectBlockTypes(String body, boolean tex) {
        if (tex) return Note.BLOCK_CODE | Note.BLOCK_TEXT;
        if (body == null || body.isEmpty()) return Note.BLOCK_TEXT;
        int mask = 0;
        boolean fenced = false;
        for (String raw : body.split("\\n", -1)) {
            String line = raw.trim();
            if (line.startsWith("```")) {
                fenced = !fenced;
                mask |= Note.BLOCK_CODE;
                continue;
            }
            if (fenced) { mask |= Note.BLOCK_CODE; continue; }
            if (line.matches("#{1,6}\\s+.*")) mask |= Note.BLOCK_HEADING;
            else if (line.matches("[-*+]\\s+\\[[ xX]\\]\\s+.*")) mask |= Note.BLOCK_TASK;
            else if (line.matches("([-*+]\\s+|\\d+[.)]\\s+).*")) mask |= Note.BLOCK_LIST;
            else if (line.startsWith(">")) mask |= Note.BLOCK_QUOTE;
            else if (line.startsWith("![") && line.contains("](")) mask |= Note.BLOCK_IMAGE;
            else if (line.startsWith("|") && line.indexOf('|', 1) > 0) mask |= Note.BLOCK_TABLE;
            else if (!line.isEmpty()) mask |= Note.BLOCK_TEXT;
        }
        return mask == 0 ? Note.BLOCK_TEXT : mask;
    }

    /**
     * Drops a leading {@code # Heading} that just repeats the note's title, so a
     * card's preview shows the note's actual first sentence instead of echoing
     * the title above it.
     */
    private static String skipTitleHeading(String body, String title) {
        if (body == null || body.isEmpty()) return body;
        int lineEnd = body.indexOf('\n');
        String first = (lineEnd < 0 ? body : body.substring(0, lineEnd)).trim();
        if (!first.startsWith("#")) return body;
        int level = 0;
        while (level < first.length() && first.charAt(level) == '#') level++;
        if (level >= first.length() || first.charAt(level) != ' ') return body;
        String heading = first.substring(level).trim();
        if (title == null || !heading.equalsIgnoreCase(title.trim())) return body;
        return lineEnd < 0 ? "" : body.substring(lineEnd + 1);
    }

    private static int findFrontMatterEnd(String raw, int from) {
        int i = from;
        int len = raw.length();
        while (i < len) {
            int lineEnd = raw.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = len;
            String line = raw.substring(i, lineEnd).trim();
            if (line.equals("---") || line.equals("...")) return i;
            i = lineEnd + 1;
        }
        return -1;
    }

    private static void applyFrontMatter(Note note, String block) {
        List<String> extra = new ArrayList<>(0);
        String[] lines = block.split("\n", -1);
        String pendingListKey = null;
        for (String rawLine : lines) {
            String line = rawLine;
            if (line.trim().isEmpty()) continue;

            // YAML block-sequence continuation, e.g. "tags:\n  - a\n  - b".
            if (pendingListKey != null && line.startsWith(" ") && line.trim().startsWith("- ")) {
                String value = line.trim().substring(2).trim();
                if (pendingListKey.equals("tags")) note.addTag(unquote(value));
                else if (pendingListKey.equals("aliases")) note.addAlias(unquote(value));
                else extra.add(line);
                continue;
            }
            pendingListKey = null;

            int colon = line.indexOf(':');
            if (colon <= 0) {
                extra.add(line);
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (key) {
                case "tags":
                case "tag":
                    if (value.isEmpty()) {
                        pendingListKey = "tags";
                    } else {
                        for (String tag : splitList(value)) note.addTag(tag);
                    }
                    break;
                case "aliases":
                case "alias":
                    if (value.isEmpty()) {
                        pendingListKey = "aliases";
                    } else {
                        note.setAliases(splitList(value));
                    }
                    break;
                case "created":
                    Long created = parseTimestamp(value);
                    if (created != null) note.setCreatedMillis(created);
                    break;
                case "emoji":
                case "icon":
                    note.setEmoji(unquote(value));
                    break;
                case "color":
                    note.setColor(parseColor(value));
                    break;
                case "pinned":
                    note.setPinned(isTrue(value));
                    break;
                case "archived":
                    note.setArchived(isTrue(value));
                    break;
                case "bookmarked":
                case "starred":
                    note.setBookmarked(isTrue(value));
                    break;
                case "title":
                    // Legacy Shard 1.x key. The file name is authoritative now, but
                    // honour it when the file name looks auto-generated.
                    if (!value.isEmpty() && looksGenerated(note.getTitle())) {
                        note.setTitle(unquote(value));
                    }
                    break;
                case "modified":
                    // Intentionally ignored: mtime is the source of truth.
                    break;
                default:
                    extra.add(line);
                    break;
            }
        }
        note.setFrontMatterExtra(extra);
    }

    private static boolean looksGenerated(String title) {
        return title == null || title.isEmpty() || title.startsWith("Untitled");
    }

    // ---------------------------------------------------------------- writing

    /** Serialises a note back to file text, front matter first. */
    public static String serialize(Note note) {
        StringBuilder head = new StringBuilder(96);
        if (note.hasStoredCreated() && note.getCreatedMillis() > 0) {
            head.append("created: ").append(iso(note.getCreatedMillis())).append('\n');
        }
        if (!note.getEmoji().isEmpty()) {
            head.append("emoji: ").append(note.getEmoji()).append('\n');
        }
        if (note.getColor() != 0) {
            head.append("color: ").append(hexColor(note.getColor())).append('\n');
        }
        if (note.isPinned()) head.append("pinned: true\n");
        if (note.isBookmarked()) head.append("bookmarked: true\n");
        if (note.isArchived()) head.append("archived: true\n");
        appendYamlList(head, "aliases", note.getAliases());
        for (String line : note.getFrontMatterExtra()) {
            head.append(line).append('\n');
        }

        String body = note.getContent() == null ? "" : note.getContent();
        if (head.length() == 0) return body;

        // YAML front matter would make a TeX document invalid. Store the same
        // portable metadata as ordinary TeX comments instead, while keeping it
        // out of the raw editor body.
        if (isTexFile(note.getId())) {
            StringBuilder out = new StringBuilder(head.length() + body.length() + 64);
            out.append(TEX_META_START).append('\n');
            for (String line : head.toString().split("\n", -1)) {
                if (!line.isEmpty()) out.append("% ").append(line).append('\n');
            }
            out.append(TEX_META_END).append('\n').append(body);
            return out.toString();
        }

        StringBuilder out = new StringBuilder(head.length() + body.length() + 16);
        out.append("---\n").append(head).append("---\n\n").append(body);
        return out.toString();
    }

    /**
     * Writes {@code text} durably: content goes to a sibling temp file which is
     * then renamed over the target, so a kill mid-write can never truncate a
     * note. Falls back to a direct write when the rename is unavailable (some
     * SD-card filesystems).
     */
    public static void writeAtomic(File target, String text) throws IOException {
        writeAtomicInternal(target, text);
        // Reported only after the bytes have landed: the cloud mirror reads
        // the file back, so telling it any earlier would upload stale content.
        // Several early returns below make a wrapper cleaner than a notify at
        // each of them.
        VaultWrites.notifyWritten(target);
    }

    private static void writeAtomicInternal(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();

        byte[] bytes = text.getBytes("UTF-8");
        File tmp = new File(parent, "." + target.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(bytes);
            out.flush();
            try {
                out.getFD().sync();
            } catch (Throwable ignored) {
                // sync is best-effort; not supported on every FUSE mount.
            }
        } finally {
            closeQuietly(out);
        }

        if (target.exists() && !target.delete()) {
            // Some providers refuse delete but allow overwrite: try direct write.
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                writeDirect(target, bytes);
                return;
            }
            return;
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            writeDirect(target, bytes);
        }
    }

    private static void writeDirect(File target, byte[] bytes) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(bytes);
            out.flush();
        } finally {
            closeQuietly(out);
        }
    }

    public static void save(File file, Note note) throws IOException {
        writeAtomic(file, serialize(note));
        note.setModifiedMillis(file.lastModified());
        note.setSizeBytes(file.length());
    }

    // ---------------------------------------------------------------- helpers

    public static String titleFromPath(String relativePath) {
        String name = relativePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        String extension = noteExtension(name);
        if (isNoteFile(name)) {
            name = name.substring(0, name.length() - extension.length());
        }
        return name;
    }

    public static boolean isNoteFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(EXT) || lower.endsWith(".markdown")
                || lower.endsWith(".txt") || lower.endsWith(TEX_EXT);
    }

    public static boolean isTexFile(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(TEX_EXT);
    }

    /** Returns the recognised note suffix, preserving the suffix's original case. */
    public static String noteExtension(String name) {
        if (name == null) return EXT;
        String lower = name.toLowerCase(Locale.ROOT);
        String[] extensions = {".markdown", TEX_EXT, ".txt", EXT};
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return name.substring(name.length() - extension.length());
            }
        }
        return EXT;
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>(2);
        String v = value.trim();
        if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1);
        for (String part : v.split(",")) {
            String tag = unquote(part.trim());
            if (!tag.isEmpty()) out.add(tag);
        }
        return out;
    }

    /** Writes a compact, unambiguous YAML sequence for an Obsidian property. */
    private static void appendYamlList(StringBuilder out, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        out.append(key).append(":\n");
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            out.append("  - \"")
                    .append(value.trim().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"\n");
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static boolean isTrue(String value) {
        String v = unquote(value).trim();
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }

    private static int parseColor(String value) {
        String v = unquote(value).trim();
        try {
            if (v.startsWith("#")) {
                if (v.length() == 7) return 0xFF000000 | Integer.parseInt(v.substring(1), 16);
                if (v.length() == 9) return (int) Long.parseLong(v.substring(1), 16);
            }
            return (int) Long.parseLong(v);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String hexColor(int color) {
        return String.format(Locale.ROOT, "#%06X", 0xFFFFFF & color);
    }

    /** Accepts both epoch millis (Shard 1.x) and ISO-8601 dates. */
    private static Long parseTimestamp(String value) {
        String v = unquote(value).trim();
        if (v.isEmpty()) return null;
        try {
            if (v.length() >= 10 && v.indexOf('-') > 0) {
                java.text.SimpleDateFormat fmt = v.length() > 10
                        ? new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                        : new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                return fmt.parse(v.length() > 19 ? v.substring(0, 19) : v).getTime();
            }
            return Long.parseLong(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String iso(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                .format(new java.util.Date(millis));
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) { }
    }
}
