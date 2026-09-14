package com.ccs.shard.block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between Markdown text and a list of {@link Block}s.
 *
 * <p>The Markdown file stays the source of truth: the editor parses on open and
 * serialises on save, and the round trip is designed to be stable — parsing the
 * output of {@link #toMarkdown} yields an equivalent block list, and formatting
 * a note does not reshuffle parts the user never touched.
 *
 * <p>Common Obsidian/GFM extensions have dedicated blocks where that improves
 * editing: callouts, transclusions, footnotes and reference definitions. Less
 * common Markdown remains ordinary text, so no content is ever silently dropped.
 */
public final class BlockDocument implements InlineMd.ReferenceResolver {

    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, String> referenceTargets = new LinkedHashMap<>();
    private final Map<String, String> footnoteDefinitions = new LinkedHashMap<>();
    private boolean definitionsDirty;

    private static final Pattern FOOTNOTE_DEFINITION = Pattern.compile(
            "^[ ]{0,3}\\[\\^([^\\]\\s]+)\\]:[\\t ]*(.*)$");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile(
            "^[ ]{0,3}\\[([^\\]^][^\\]]*)\\]:[\\t ]*(?:<([^>]+)>|(\\S+))(?:[\\t ]+.*)?$");

    public BlockDocument() {}

    public static BlockDocument parse(String markdown) {
        BlockDocument doc = new BlockDocument();
        doc.parseInto(markdown == null ? "" : markdown);
        if (doc.blocks.isEmpty()) doc.blocks.add(Block.paragraph(""));
        return doc;
    }

    public List<Block> blocks() { return blocks; }

    public int size() { return blocks.size(); }

    public Block get(int index) {
        return index >= 0 && index < blocks.size() ? blocks.get(index) : null;
    }

    public int indexOf(Block block) { return blocks.indexOf(block); }

    public int indexOfUid(long uid) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).uid == uid) return i;
        }
        return -1;
    }

    public void add(Block block) {
        blocks.add(block);
        definitionsDirty = true;
    }

    public void add(int index, Block block) {
        blocks.add(Math.max(0, Math.min(index, blocks.size())), block);
        definitionsDirty = true;
    }

    public Block remove(int index) {
        if (index < 0 || index >= blocks.size()) return null;
        definitionsDirty = true;
        return blocks.remove(index);
    }

    public void move(int from, int to) {
        if (from < 0 || from >= blocks.size()) return;
        Block block = blocks.remove(from);
        blocks.add(Math.max(0, Math.min(to, blocks.size())), block);
        definitionsDirty = true;
    }

    public void clear() {
        blocks.clear();
        referenceTargets.clear();
        footnoteDefinitions.clear();
        definitionsDirty = false;
    }

    /** Marks parsed reference/footnote lookup data stale after an in-place edit. */
    public void markDefinitionsDirty() { definitionsDirty = true; }

    @Override
    public String referenceTarget(String label) {
        rebuildDefinitionsIfNeeded();
        return referenceTargets.get(normalizeReferenceLabel(label));
    }

    @Override
    public boolean hasFootnote(String id) {
        rebuildDefinitionsIfNeeded();
        return footnoteDefinitions.containsKey(normalizeFootnoteId(id));
    }

    /** Index of a footnote's definition, used to navigate there from {@code [^id]}. */
    public int indexOfFootnote(String id) {
        String wanted = normalizeFootnoteId(id);
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.type != BlockType.FOOTNOTE) continue;
            Matcher matcher = FOOTNOTE_DEFINITION.matcher(firstLine(block.text));
            if (matcher.matches() && wanted.equals(normalizeFootnoteId(matcher.group(1)))) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- parsing

    private void parseInto(String markdown) {
        List<String> lines = splitLines(markdown);
        List<Integer> listIndentColumns = new ArrayList<>();
        int i = 0;
        int n = lines.size();

        while (i < n) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                listIndentColumns.clear();
                i++;
                continue;
            }

            // Fenced code.
            String fence = fenceMarker(trimmed);
            if (fence != null) {
                listIndentColumns.clear();
                String language = trimmed.substring(fence.length()).trim();
                StringBuilder body = new StringBuilder();
                i++;
                while (i < n) {
                    String codeLine = lines.get(i);
                    if (codeLine.trim().startsWith(fence)
                            && fenceMarker(codeLine.trim()) != null) {
                        i++;
                        break;
                    }
                    if (body.length() > 0) body.append('\n');
                    body.append(codeLine);
                    i++;
                }
                blocks.add(Block.code(language, body.toString()));
                continue;
            }

            // Pipe table: needs the delimiter row to avoid eating ordinary text.
            if (TableData.isTableLine(line) && i + 1 < n
                    && TableData.isDelimiterRow(lines.get(i + 1))) {
                listIndentColumns.clear();
                List<String> tableLines = new ArrayList<>();
                while (i < n && TableData.isTableLine(lines.get(i))) {
                    tableLines.add(lines.get(i));
                    i++;
                }
                blocks.add(Block.table(TableData.parse(tableLines)));
                continue;
            }

            // Thematic break.
            if (isThematicBreak(trimmed)) {
                listIndentColumns.clear();
                blocks.add(Block.divider());
                i++;
                continue;
            }

            // ATX heading.
            int level = headingLevel(trimmed);
            if (level > 0) {
                listIndentColumns.clear();
                String text = trimmed.substring(level).trim();
                // Strip an optional closing run of hashes.
                while (text.endsWith("#")) text = text.substring(0, text.length() - 1).trim();
                Block block = Block.text(headingType(level), decodeSoftBreaks(text));
                block.indent = level;
                blocks.add(block);
                i++;
                continue;
            }

            // Block quote or callout, possibly spanning several lines.
            if (trimmed.startsWith(">")) {
                listIndentColumns.clear();
                List<String> quoteLines = new ArrayList<>();
                while (i < n && lines.get(i).trim().startsWith(">")) {
                    quoteLines.add(stripQuoteMarker(lines.get(i)));
                    i++;
                }
                blocks.add(buildQuote(quoteLines));
                continue;
            }

            // GFM footnote definitions may span indented continuation lines.
            Matcher footnote = FOOTNOTE_DEFINITION.matcher(line);
            if (footnote.matches()) {
                listIndentColumns.clear();
                StringBuilder definition = new StringBuilder(line);
                String id = normalizeFootnoteId(footnote.group(1));
                i++;
                while (i < n && isDefinitionContinuation(lines.get(i))) {
                    definition.append('\n').append(lines.get(i));
                    i++;
                }
                blocks.add(Block.footnote(definition.toString()));
                footnoteDefinitions.put(id, definition.toString());
                continue;
            }

            // CommonMark/GFM reference links: [manual]: https://example.com "Title".
            Matcher reference = REFERENCE_DEFINITION.matcher(line);
            if (reference.matches()) {
                listIndentColumns.clear();
                blocks.add(Block.reference(line));
                referenceTargets.put(normalizeReferenceLabel(reference.group(1)),
                        reference.group(2) != null ? reference.group(2) : reference.group(3));
                i++;
                continue;
            }

            // Obsidian's ![[target#heading|label]] transclusion syntax.
            if (isObsidianEmbed(trimmed)) {
                listIndentColumns.clear();
                blocks.add(Block.embed(trimmed));
                i++;
                continue;
            }

            // Task list item.
            int indentColumns = leadingIndentColumns(line);
            String content = line.trim();
            Boolean todoChecked = taskState(content);
            if (todoChecked != null) {
                Block block = Block.todo(decodeSoftBreaks(stripTaskMarker(content)), todoChecked);
                block.indent = listDepth(listIndentColumns, indentColumns);
                blocks.add(block);
                i++;
                continue;
            }

            if (isBulletItem(content)) {
                Block block = Block.text(BlockType.BULLET,
                        decodeSoftBreaks(content.substring(listMarkerLength(content)).trim()));
                block.indent = listDepth(listIndentColumns, indentColumns);
                blocks.add(block);
                i++;
                continue;
            }

            int numberedPrefix = numberedPrefixLength(content);
            if (numberedPrefix > 0) {
                Block block = Block.text(BlockType.NUMBERED,
                        decodeSoftBreaks(content.substring(numberedPrefix).trim()));
                block.indent = listDepth(listIndentColumns, indentColumns);
                blocks.add(block);
                i++;
                continue;
            }

            // A lone image becomes an image block; inline images stay in the text.
            String[] image = standaloneImage(content);
            if (image != null) {
                listIndentColumns.clear();
                blocks.add(Block.image(image[1], image[0]));
                i++;
                continue;
            }

            // Paragraph: consume until a blank line or the start of another construct.
            listIndentColumns.clear();
            StringBuilder paragraph = new StringBuilder(content);
            i++;
            while (i < n) {
                String next = lines.get(i);
                String nextTrimmed = next.trim();
                if (nextTrimmed.isEmpty() || startsNewBlock(next, lines, i)) break;
                paragraph.append('\n').append(nextTrimmed);
                i++;
            }
            blocks.add(Block.paragraph(decodeSoftBreaks(paragraph.toString())));
        }
    }

    /** True when {@code line} begins a construct that must not be folded into a paragraph. */
    private static boolean startsNewBlock(String line, List<String> lines, int index) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return true;
        if (fenceMarker(trimmed) != null) return true;
        if (isThematicBreak(trimmed)) return true;
        if (headingLevel(trimmed) > 0) return true;
        if (trimmed.startsWith(">")) return true;
        if (FOOTNOTE_DEFINITION.matcher(line).matches()) return true;
        if (REFERENCE_DEFINITION.matcher(line).matches()) return true;
        if (isObsidianEmbed(trimmed)) return true;
        if (taskState(trimmed) != null) return true;
        if (isBulletItem(trimmed)) return true;
        if (numberedPrefixLength(trimmed) > 0) return true;
        if (standaloneImage(trimmed) != null) return true;
        return TableData.isTableLine(line)
                && index + 1 < lines.size()
                && TableData.isDelimiterRow(lines.get(index + 1));
    }

    private static Block buildQuote(List<String> quoteLines) {
        if (quoteLines.isEmpty()) return Block.text(BlockType.QUOTE, "");
        String first = quoteLines.get(0).trim();
        // Obsidian/GitHub callout syntax: "> [!warning] Title".
        if (first.startsWith("[!")) {
            int close = first.indexOf(']');
            if (close > 2) {
                String kind = first.substring(2, close).trim().toLowerCase(java.util.Locale.ROOT);
                int bodyStart = close + 1;
                String fold = "";
                if (bodyStart < first.length()) {
                    char modifier = first.charAt(bodyStart);
                    if (modifier == '+' || modifier == '-') {
                        fold = String.valueOf(modifier);
                        bodyStart++;
                    }
                }
                StringBuilder body = new StringBuilder(first.substring(bodyStart).trim());
                for (int i = 1; i < quoteLines.size(); i++) {
                    if (body.length() > 0) body.append('\n');
                    body.append(quoteLines.get(i).trim());
                }
                Block callout = Block.callout(kind, body.toString().trim());
                callout.calloutFold = fold;
                return callout;
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < quoteLines.size(); i++) {
            if (i > 0) body.append('\n');
            body.append(quoteLines.get(i).trim());
        }
        return Block.text(BlockType.QUOTE, body.toString().trim());
    }

    // ---------------------------------------------------------------- serializing

    public String toMarkdown() {
        StringBuilder out = new StringBuilder(estimateLength());
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            appendBlock(out, block);
            if (i == blocks.size() - 1) break;
            Block next = blocks.get(i + 1);
            out.append(separatorBetween(block, next));
        }
        // Notes end with exactly one newline.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        if (out.length() > 0) out.append('\n');
        return out.toString();
    }

    private int estimateLength() {
        int total = 32;
        for (Block block : blocks) total += block.text.length() + 8;
        return total;
    }

    /**
     * List items of the same kind stay adjacent so they render as one list;
     * everything else is separated by a blank line.
     */
    private static String separatorBetween(Block current, Block next) {
        if (current.type.isList() && next.type.isList()) return "\n";
        if (current.type.isDefinition() && next.type.isDefinition()) return "\n";
        return "\n\n";
    }

    private static void appendBlock(StringBuilder out, Block block) {
        switch (block.type) {
            case HEADING_1:
            case HEADING_2:
            case HEADING_3: {
                int level = block.indent > 0 ? Math.min(6, block.indent) : headingLevelOf(block.type);
                for (int i = 0; i < level; i++) out.append('#');
                out.append(' ').append(inlineWithSoftBreaks(block.text));
                break;
            }
            case BULLET:
                appendIndent(out, block.indent);
                out.append("- ").append(inlineWithSoftBreaks(block.text));
                break;
            case NUMBERED:
                appendIndent(out, block.indent);
                out.append("1. ").append(inlineWithSoftBreaks(block.text));
                break;
            case TODO:
                appendIndent(out, block.indent);
                out.append(block.checked ? "- [x] " : "- [ ] ")
                        .append(inlineWithSoftBreaks(block.text));
                break;
            case QUOTE:
                appendQuoted(out, block.text, null);
                break;
            case CALLOUT:
                appendQuoted(out, block.text, block.calloutKind, block.calloutFold);
                break;
            case EMBED:
            case FOOTNOTE:
            case REFERENCE:
                out.append(block.text);
                break;
            case CODE:
                out.append("```").append(block.language == null ? "" : block.language).append('\n');
                out.append(block.text);
                if (block.text.isEmpty() || !block.text.endsWith("\n")) out.append('\n');
                out.append("```");
                break;
            case TABLE:
                if (block.table != null) out.append(block.table.toMarkdown());
                break;
            case IMAGE:
                out.append("![").append(block.imageAlt == null ? "" : block.imageAlt)
                        .append("](").append(block.imageRef).append(')');
                break;
            case DIVIDER:
                out.append("---");
                break;
            case PARAGRAPH:
            default:
                out.append(block.text);
                break;
        }
    }

    private static void appendIndent(StringBuilder out, int indent) {
        // Four columns is the CommonMark nesting convention. The parser also
        // accepts two-column legacy Shard lists and preserves their hierarchy.
        for (int i = 0; i < indent; i++) out.append("    ");
    }

    private static void appendQuoted(StringBuilder out, String text, String calloutKind) {
        appendQuoted(out, text, calloutKind, "");
    }

    private static void appendQuoted(StringBuilder out, String text, String calloutKind,
                                     String calloutFold) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        if (calloutKind != null) {
            out.append("> [!").append(calloutKind).append(']');
            if ("+".equals(calloutFold) || "-".equals(calloutFold)) out.append(calloutFold);
            if (lines.length > 0 && !lines[0].isEmpty()) out.append(' ').append(lines[0]);
            for (int i = 1; i < lines.length; i++) out.append("\n> ").append(lines[i]);
            return;
        }
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append("> ").append(lines[i]);
        }
    }

    /** Encodes a soft break without breaking heading or list Markdown syntax. */
    private static String inlineWithSoftBreaks(String text) {
        if (text == null) return "";
        if (text.indexOf('\n') < 0) return text;
        return text.replace("\n", "<br>");
    }

    /** Restores the portable Markdown/HTML representation used for soft breaks. */
    private static String decodeSoftBreaks(String text) {
        if (text == null || text.indexOf('<') < 0) return text;
        return text.replaceAll("(?i)<br\\s*/?>", "\n");
    }

    // ---------------------------------------------------------------- line helpers

    public static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(stripCarriageReturn(text.substring(start, i)));
                start = i + 1;
            }
        }
        lines.add(stripCarriageReturn(text.substring(start)));
        return lines;
    }

    private static String stripCarriageReturn(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String fenceMarker(String trimmed) {
        if (trimmed.startsWith("```")) return "```";
        if (trimmed.startsWith("~~~")) return "~~~";
        return null;
    }

    private static boolean isThematicBreak(String trimmed) {
        if (trimmed.length() < 3) return false;
        char c = trimmed.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == c) count++;
            else if (ch != ' ' && ch != '\t') return false;
        }
        return count >= 3;
    }

    private static int headingLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
        if (level == 0 || level > 6) return 0;
        if (level >= trimmed.length()) return 0;
        return trimmed.charAt(level) == ' ' ? level : 0;
    }

    public static BlockType headingType(int level) {
        if (level <= 1) return BlockType.HEADING_1;
        if (level == 2) return BlockType.HEADING_2;
        return BlockType.HEADING_3;
    }

    public static int headingLevelOf(BlockType type) {
        switch (type) {
            case HEADING_1: return 1;
            case HEADING_2: return 2;
            case HEADING_3: return 3;
            default: return 0;
        }
    }

    private static String stripQuoteMarker(String line) {
        String t = line.trim();
        if (t.startsWith(">")) {
            t = t.substring(1);
            if (t.startsWith(" ")) t = t.substring(1);
        }
        return t;
    }

    /** Leading indentation in visual columns; a tab counts as four columns. */
    private static int leadingIndentColumns(String line) {
        int spaces = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') spaces++;
            else if (c == '\t') spaces += 4;
            else break;
        }
        return spaces;
    }

    /**
     * Turns the actually used indentation into a hierarchy. This accepts both
     * four-space CommonMark nesting and old two-space Shard notes, without
     * guessing from an arbitrary global "spaces per level" rule.
     */
    private static int listDepth(List<Integer> columns, int indentColumns) {
        int existing = columns.indexOf(indentColumns);
        if (existing >= 0) {
            while (columns.size() > existing + 1) columns.remove(columns.size() - 1);
            return Math.min(5, existing);
        }
        while (!columns.isEmpty() && indentColumns < columns.get(columns.size() - 1)) {
            columns.remove(columns.size() - 1);
        }
        if (!columns.isEmpty() && indentColumns == columns.get(columns.size() - 1)) {
            return columns.size() - 1;
        }
        if (columns.isEmpty()) {
            columns.add(indentColumns);
            return 0;
        }
        columns.add(indentColumns);
        return Math.min(5, columns.size() - 1);
    }

    /** Returns null when the line is not a task item, else its checked state. */
    private static Boolean taskState(String trimmed) {
        if (trimmed.length() < 5) return null;
        char bullet = trimmed.charAt(0);
        if (bullet != '-' && bullet != '*' && bullet != '+') return null;
        if (!Character.isWhitespace(trimmed.charAt(1)) || trimmed.charAt(2) != '[') return null;
        char state = trimmed.charAt(3);
        if (trimmed.charAt(4) != ']') return null;
        if (state == ' ') return Boolean.FALSE;
        if (state == 'x' || state == 'X') return Boolean.TRUE;
        return null;
    }

    private static String stripTaskMarker(String trimmed) {
        String rest = trimmed.substring(5);
        while (!rest.isEmpty() && Character.isWhitespace(rest.charAt(0))) rest = rest.substring(1);
        return rest;
    }

    private static boolean isBulletItem(String trimmed) {
        if (trimmed.length() < 2) return false;
        char c = trimmed.charAt(0);
        return (c == '-' || c == '*' || c == '+') && Character.isWhitespace(trimmed.charAt(1));
    }

    private static int listMarkerLength(String trimmed) {
        int i = 1;
        while (i < trimmed.length() && Character.isWhitespace(trimmed.charAt(i))) i++;
        return i;
    }

    private static int numberedPrefixLength(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) i++;
        if (i == 0 || i > 9 || i + 1 >= trimmed.length()) return 0;
        char delimiter = trimmed.charAt(i);
        if (delimiter != '.' && delimiter != ')') return 0;
        int after = i + 1;
        if (!Character.isWhitespace(trimmed.charAt(after))) return 0;
        while (after < trimmed.length() && Character.isWhitespace(trimmed.charAt(after))) after++;
        return after;
    }

    /** Returns {@code {alt, ref}} when the whole line is a single image. */
    private static String[] standaloneImage(String trimmed) {
        if (!trimmed.startsWith("![") || !trimmed.endsWith(")")) return null;
        java.util.regex.Matcher m = com.ccs.shard.core.Md.IMAGE.matcher(trimmed);
        if (!m.matches()) return null;
        return new String[]{m.group(1) == null ? "" : m.group(1), m.group(2)};
    }

    private static boolean isObsidianEmbed(String trimmed) {
        if (!trimmed.startsWith("![[") || !trimmed.endsWith("]]")) return false;
        return trimmed.length() > 4 && trimmed.indexOf("]]", 3) == trimmed.length() - 2;
    }

    private static boolean isDefinitionContinuation(String line) {
        return line.startsWith("    ") || line.startsWith("\t");
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private void rebuildDefinitionsIfNeeded() {
        if (!definitionsDirty) return;
        referenceTargets.clear();
        footnoteDefinitions.clear();
        for (Block block : blocks) {
            if (block.type == BlockType.REFERENCE) {
                Matcher reference = REFERENCE_DEFINITION.matcher(firstLine(block.text));
                if (reference.matches()) {
                    referenceTargets.put(normalizeReferenceLabel(reference.group(1)),
                            reference.group(2) != null ? reference.group(2) : reference.group(3));
                }
            } else if (block.type == BlockType.FOOTNOTE) {
                Matcher footnote = FOOTNOTE_DEFINITION.matcher(firstLine(block.text));
                if (footnote.matches()) {
                    footnoteDefinitions.put(normalizeFootnoteId(footnote.group(1)), block.text);
                }
            }
        }
        definitionsDirty = false;
    }

    private static String normalizeReferenceLabel(String label) {
        if (label == null) return "";
        return label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeFootnoteId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
