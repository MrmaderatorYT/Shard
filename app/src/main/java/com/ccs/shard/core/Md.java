package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown text utilities shared by the indexer, the editor and the exporters.
 *
 * <p>All patterns are compiled once. The scanning helpers are hand-written
 * character loops rather than regexes where they run per keystroke or per note
 * during indexing, which measurably matters on budget SoCs.
 */
public final class Md {

    private Md() {}

    public static final Pattern WIKI_LINK =
            Pattern.compile("\\[\\[([^\\]|#]+)(?:#([^\\]|]+))?(?:\\|([^\\]]+))?\\]\\]");

    public static final Pattern MD_LINK =
            Pattern.compile("\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    public static final Pattern IMAGE =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    public static final Pattern URL = Pattern.compile(
            "(https?://[-\\w@:%.+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b[-\\w@:%+.~#?&/=]*)");

    /** Inline tag: {@code #tag}, {@code #tag/child}. Must not follow a word character. */
    public static final Pattern TAG =
            Pattern.compile("(?<![\\w&#])#([\\p{L}][\\p{L}\\p{N}_/-]*)");

    // ---------------------------------------------------------------- extraction

    /** Wiki-link targets in document order, normalised to link keys, without duplicates. */
    public static List<String> wikiLinkKeys(CharSequence content) {
        List<String> out = new ArrayList<>(4);
        if (content == null || content.length() == 0) return out;
        Matcher m = WIKI_LINK.matcher(content);
        while (m.find()) {
            String key = Note.linkKey(m.group(1));
            if (!key.isEmpty() && !out.contains(key)) out.add(key);
        }
        return out;
    }

    /** Inline {@code #tags}, excluding those inside fenced code blocks. */
    public static List<String> inlineTags(CharSequence content) {
        List<String> out = new ArrayList<>(2);
        if (content == null || content.length() == 0) return out;
        Matcher m = TAG.matcher(stripFencedCode(content));
        while (m.find()) {
            String tag = m.group(1);
            if (!out.contains(tag)) out.add(tag);
        }
        return out;
    }

    public static Set<String> tagSet(CharSequence content) {
        return new LinkedHashSet<>(inlineTags(content));
    }

    /** Returns a tag suitable for Markdown, without its leading {@code #}. */
    public static String normalizeTag(String value) {
        if (value == null) return "";
        String tag = value.trim();
        while (tag.startsWith("#")) tag = tag.substring(1).trim();
        if (tag.isEmpty() || !TAG.matcher("#" + tag).matches()) return "";
        return tag;
    }

    /** Adds or removes one inline tag while leaving fenced code untouched. */
    public static String withInlineTag(CharSequence content, String value, boolean add) {
        String tag = normalizeTag(value);
        String text = content == null ? "" : content.toString();
        if (tag.isEmpty()) return text;
        if (add) {
            if (inlineTags(text).contains(tag)) return text;
            if (text.isEmpty()) return "#" + tag;
            String separator = text.endsWith("\n") ? "\n" : "\n\n";
            return text + separator + "#" + tag;
        }
        return removeInlineTag(text, tag);
    }

    private static String removeInlineTag(String text, String tag) {
        if (text.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        boolean inFence = false;
        for (String line : lines) {
            String lineForFence = line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line;
            if (isFenceLine(lineForFence, 0, lineForFence.length())) {
                kept.add(line);
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                kept.add(line);
                continue;
            }

            Matcher matcher = TAG.matcher(line);
            StringBuilder changed = new StringBuilder(line.length());
            int last = 0;
            boolean removed = false;
            while (matcher.find()) {
                if (!tag.equals(matcher.group(1))) continue;
                changed.append(line, last, matcher.start());
                last = matcher.end();
                removed = true;
            }
            if (!removed) {
                kept.add(line);
                continue;
            }
            changed.append(line, last, line.length());
            String replacement = changed.toString();
            if (!replacement.trim().isEmpty()) kept.add(replacement);
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(kept.get(i));
        }
        return out.toString();
    }

    /** Local image/attachment references (skips absolute URLs). */
    public static List<String> attachmentRefs(CharSequence content) {
        List<String> out = new ArrayList<>(2);
        if (content == null) return out;
        Matcher m = IMAGE.matcher(content);
        while (m.find()) {
            String target = m.group(2);
            if (target == null) continue;
            if (target.startsWith("http://") || target.startsWith("https://")
                    || target.startsWith("data:")) continue;
            if (!out.contains(target)) out.add(target);
        }
        return out;
    }

    /** Replaces fenced code block bodies with blanks so scanners ignore their contents. */
    public static CharSequence stripFencedCode(CharSequence content) {
        int len = content.length();
        if (len == 0) return content;
        StringBuilder sb = null;
        boolean inFence = false;
        int i = 0;
        while (i < len) {
            int lineEnd = indexOfNewline(content, i);
            boolean fence = isFenceLine(content, i, lineEnd);
            if (fence) {
                if (sb == null) sb = new StringBuilder(content);
                blank(sb, i, lineEnd);
                inFence = !inFence;
            } else if (inFence) {
                if (sb == null) sb = new StringBuilder(content);
                blank(sb, i, lineEnd);
            }
            i = lineEnd + 1;
        }
        return sb != null ? sb : content;
    }

    private static void blank(StringBuilder sb, int from, int to) {
        for (int i = from; i < to && i < sb.length(); i++) sb.setCharAt(i, ' ');
    }

    private static boolean isFenceLine(CharSequence s, int from, int to) {
        int i = from;
        int spaces = 0;
        while (i < to && s.charAt(i) == ' ' && spaces < 4) { i++; spaces++; }
        if (to - i < 3) return false;
        char c = s.charAt(i);
        if (c != '`' && c != '~') return false;
        return s.charAt(i + 1) == c && s.charAt(i + 2) == c;
    }

    public static int indexOfNewline(CharSequence s, int from) {
        for (int i = from, n = s.length(); i < n; i++) {
            if (s.charAt(i) == '\n') return i;
        }
        return s.length();
    }


    /**
     * The text a reader would actually see on one line: block markers, inline
     * syntax and table plumbing removed.
     *
     * <p>Table rows become their cell contents; the {@code |---|---|} delimiter
     * row becomes nothing. Without this an empty three-column table counted as
     * nineteen words and previewed as a row of pipes.
     */
    public static String visibleLine(CharSequence content, int from, int to) {
        if (to <= from) return "";
        String raw = content.subSequence(from, to).toString().trim();
        if (raw.isEmpty()) return "";
        if (isTableDelimiter(raw)) return "";
        if (raw.startsWith("|")) return tableRowText(raw);
        return plainLine(content, from, to);
    }

    /** True for a pipe-table delimiter row such as {@code | :-- | --: |}. */
    public static boolean isTableDelimiter(String trimmed) {
        if (!trimmed.startsWith("|")) return false;
        boolean sawDash = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-') sawDash = true;
            else if (c != '|' && c != ':' && c != ' ') return false;
        }
        return sawDash;
    }

    /** Cell text of a pipe-table row, joined with spaces. */
    private static String tableRowText(String row) {
        StringBuilder out = new StringBuilder(row.length());
        int start = row.startsWith("|") ? 1 : 0;
        int end = row.endsWith("|") ? row.length() - 1 : row.length();
        StringBuilder cell = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < end && row.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                appendCell(out, cell);
            } else {
                cell.append(c);
            }
        }
        appendCell(out, cell);
        return out.toString().trim();
    }

    private static void appendCell(StringBuilder out, StringBuilder cell) {
        String text = stripInline(cell.toString().trim());
        cell.setLength(0);
        if (text.isEmpty()) return;
        if (out.length() > 0) out.append("  ");
        out.append(text);
    }

    /**
     * Words a reader would count: syntax, code blocks and table plumbing
     * excluded. Code block bodies are deliberately skipped — a snippet is not
     * prose, and counting it makes "words" meaningless in a technical note.
     */
    public static int wordCount(CharSequence content) {
        if (content == null || content.length() == 0) return 0;
        int words = 0;
        boolean inFence = false;
        int i = 0;
        int len = content.length();
        while (i < len) {
            int lineEnd = indexOfNewline(content, i);
            if (isFenceLine(content, i, lineEnd)) {
                inFence = !inFence;
                i = lineEnd + 1;
                continue;
            }
            if (!inFence) {
                words += Note.countWords(visibleLine(content, i, lineEnd));
            }
            i = lineEnd + 1;
        }
        return words;
    }

    // ---------------------------------------------------------------- excerpts

    /**
     * Human-readable one-liner for note cards: strips syntax, code fences, front
     * matter leftovers and heading markers so the list never shows raw
     * {@code ```java} or {@code # 2026-06-23}.
     */
    public static String excerpt(CharSequence content, int maxChars) {
        if (content == null || content.length() == 0) return "";
        StringBuilder out = new StringBuilder(Math.min(maxChars + 16, 256));
        boolean inFence = false;
        int i = 0;
        int len = content.length();
        while (i < len && out.length() < maxChars) {
            int lineEnd = indexOfNewline(content, i);
            if (isFenceLine(content, i, lineEnd)) {
                inFence = !inFence;
                i = lineEnd + 1;
                continue;
            }
            if (!inFence) {
                String line = visibleLine(content, i, lineEnd);
                if (!line.isEmpty()) {
                    if (out.length() > 0) out.append(" · ");
                    out.append(line);
                }
            }
            i = lineEnd + 1;
        }
        String result = out.toString().trim();
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars).trim() + "…";
        }
        return result;
    }

    /** Strips block and inline markdown from a single line, returning display text. */
    public static String plainLine(CharSequence content, int from, int to) {
        int i = from;
        // Leading block markers.
        while (i < to) {
            char c = content.charAt(i);
            if (c == ' ' || c == '\t' || c == '>') { i++; continue; }
            if (c == '#') {
                // Only a heading when the run of hashes is followed by a space;
                // otherwise this is a #tag and the hash is part of the word.
                int j = i;
                while (j < to && content.charAt(j) == '#') j++;
                if (j < to && content.charAt(j) == ' ') {
                    i = j + 1;
                    continue;
                }
                break;
            }
            if ((c == '-' || c == '*' || c == '+') && i + 1 < to && content.charAt(i + 1) == ' ') {
                i += 2;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < to && Character.isDigit(content.charAt(j))) j++;
                if (j + 1 < to && content.charAt(j) == '.' && content.charAt(j + 1) == ' ') {
                    i = j + 2;
                    continue;
                }
            }
            if (c == '[' && i + 3 < to && content.charAt(i + 2) == ']' && content.charAt(i + 1) != '[') {
                // Task list marker "[ ] " / "[x] "
                i += 3;
                while (i < to && content.charAt(i) == ' ') i++;
                continue;
            }
            break;
        }
        if (i >= to) return "";
        String line = content.subSequence(i, to).toString();
        if (isRule(line)) return "";
        return stripInline(line);
    }

    private static boolean isRule(String line) {
        String t = line.trim();
        if (t.length() < 3) return false;
        char c = t.charAt(0);
        if (c != '-' && c != '*' && c != '_' && c != '=') return false;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != c && t.charAt(i) != ' ') return false;
        }
        return true;
    }

    /**
     * Plain-text rendering of a whole snippet: block markers per line, inline
     * syntax throughout. Used for search result previews, which would otherwise
     * show raw {@code [[links]]} and {@code ##} to the user.
     */
    public static String plainText(CharSequence content, int maxChars) {
        if (content == null || content.length() == 0) return "";
        StringBuilder out = new StringBuilder(Math.min(maxChars + 16, 512));
        int i = 0;
        int len = content.length();
        while (i < len && out.length() < maxChars) {
            int lineEnd = indexOfNewline(content, i);
            String line = visibleLine(content, i, lineEnd);
            if (!line.isEmpty()) {
                if (out.length() > 0) out.append(' ');
                out.append(line);
            }
            i = lineEnd + 1;
        }
        String result = out.toString().trim();
        return result.length() > maxChars ? result.substring(0, maxChars).trim() + "…" : result;
    }

    /** Removes inline emphasis/link/code syntax, keeping the visible text. */
    public static String stripInline(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text;
        if (s.indexOf('[') >= 0 || s.indexOf('!') >= 0) {
            s = IMAGE.matcher(s).replaceAll("$1");
            s = MD_LINK.matcher(s).replaceAll("$1");
            s = replaceWikiLinksWithLabel(s);
        }
        if (s.indexOf('*') < 0 && s.indexOf('`') < 0 && s.indexOf('~') < 0) {
            return s.trim();
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Emphasis and code markers carry no meaning once rendered as plain text.
            if (c == '*' || c == '`' || c == '~') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** {@code [[Target|Alias]]} becomes {@code Alias}; {@code [[Target]]} becomes {@code Target}. */
    public static String replaceWikiLinksWithLabel(String text) {
        Matcher m = WIKI_LINK.matcher(text);
        if (!m.find()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        do {
            sb.append(text, last, m.start());
            String alias = m.group(3);
            sb.append(alias != null ? alias.trim() : m.group(1).trim());
            last = m.end();
        } while (m.find());
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** First heading in the document, used as a title fallback. */
    public static String firstHeading(CharSequence content) {
        if (content == null) return null;
        int i = 0;
        int len = content.length();
        int guard = 0;
        while (i < len && guard++ < 200) {
            int lineEnd = indexOfNewline(content, i);
            if (lineEnd > i && content.charAt(i) == '#') {
                int j = i;
                while (j < lineEnd && content.charAt(j) == '#') j++;
                if (j < lineEnd && content.charAt(j) == ' ') {
                    String heading = content.subSequence(j + 1, lineEnd).toString().trim();
                    if (!heading.isEmpty()) return stripInline(heading);
                }
            }
            i = lineEnd + 1;
        }
        return null;
    }

    /** Sanitises a note title into a safe file name (without extension). */
    public static String safeFileName(String title) {
        if (title == null || title.trim().isEmpty()) return "Untitled";
        String s = title.trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20) {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        if (out.isEmpty()) out = "Untitled";
        if (out.length() > 120) out = out.substring(0, 120).trim();
        return out;
    }
}
