package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure smart-indentation rules shared by the TeX editor and unit tests. */
public final class TexIndentation {

    public static final int WIDTH = 2;
    private static final String UNIT = "  ";

    private TexIndentation() {}

    /** Text to insert immediately after a freshly entered newline. */
    public static Expansion newlineExpansion(CharSequence text, int cursor) {
        if (text == null || cursor <= 0 || cursor > text.length()
                || text.charAt(cursor - 1) != '\n') return null;

        int previousStart = lineStart(text, cursor - 1);
        int previousEnd = cursor - 1;
        String previousLine = text.subSequence(previousStart, previousEnd).toString();
        String indent = normalizedIndent(previousLine);
        String code = withoutComment(previousLine).trim();
        boolean opensBlock = opensBlock(code);
        String innerIndent = indent + (opensBlock ? UNIT : "");

        int nextEnd = indexOf(text, '\n', cursor);
        if (nextEnd < 0) nextEnd = text.length();
        String next = text.subSequence(cursor, nextEnd).toString().trim();
        if (opensBlock && startsWithCloser(next)) {
            String insertion = innerIndent + "\n" + indent;
            return new Expansion(insertion, innerIndent.length());
        }
        return new Expansion(innerIndent, innerIndent.length());
    }

    /** Spaces needed to reach the next two-column tab stop. */
    public static String tabSpaces(CharSequence text, int cursor) {
        if (text == null || cursor < 0 || cursor > text.length()) return UNIT;
        int start = lineStart(text, cursor);
        int column = 0;
        for (int i = start; i < cursor; i++) {
            column += text.charAt(i) == '\t' ? WIDTH - (column % WIDTH) : 1;
        }
        int count = WIDTH - (column % WIDTH);
        StringBuilder spaces = new StringBuilder(count);
        for (int i = 0; i < count; i++) spaces.append(' ');
        return spaces.toString();
    }

    /** Per-line edits for indenting or outdenting all touched lines. */
    public static List<LineEdit> lineEdits(CharSequence text, int selectionStart,
                                            int selectionEnd, boolean outdent) {
        if (text == null || selectionStart < 0 || selectionEnd < selectionStart
                || selectionEnd > text.length()) return Collections.emptyList();
        int first = lineStart(text, selectionStart);
        int effectiveEnd = selectionEnd;
        if (selectionEnd > selectionStart && selectionEnd > 0
                && text.charAt(selectionEnd - 1) == '\n') {
            effectiveEnd = selectionEnd - 1;
        }

        List<LineEdit> edits = new ArrayList<>();
        int line = first;
        while (line <= effectiveEnd && line <= text.length()) {
            if (outdent) {
                int remove = removableIndent(text, line);
                if (remove > 0) edits.add(new LineEdit(line, remove, ""));
            } else {
                edits.add(new LineEdit(line, 0, UNIT));
            }
            int newline = indexOf(text, '\n', line);
            if (newline < 0 || newline >= effectiveEnd) break;
            line = newline + 1;
        }
        return edits;
    }

    /** Removes one indent level when a line starts with \end{...} or a closer. */
    public static LineEdit closingLineEdit(CharSequence text, int cursor) {
        if (text == null || cursor < 0 || cursor > text.length()) return null;
        int start = lineStart(text, cursor);
        int content = start;
        while (content < text.length()) {
            char c = text.charAt(content);
            if (c != ' ' && c != '\t') break;
            content++;
        }
        if (content == start || content >= text.length()) return null;
        int end = indexOf(text, '\n', content);
        if (end < 0) end = text.length();
        String line = text.subSequence(content, end).toString();
        if (!line.startsWith("\\end{") && !line.startsWith("}")
                && !line.startsWith("]") && !line.startsWith(")")) return null;

        if (text.charAt(content - 1) == '\t') return new LineEdit(content - 1, 1, "");
        int remove = 0;
        int from = content;
        while (from > start && remove < WIDTH && text.charAt(from - 1) == ' ') {
            from--;
            remove++;
        }
        return remove == 0 ? null : new LineEdit(from, remove, "");
    }

    /** Maps a caret or selection offset through a set of edits in original coordinates. */
    public static int adjustedOffset(int offset, List<LineEdit> edits) {
        int adjusted = offset;
        int accumulated = 0;
        for (LineEdit edit : edits) {
            int position = edit.start + accumulated;
            int originalEnd = position + edit.deleteCount;
            if (adjusted <= position) continue;
            if (adjusted <= originalEnd) adjusted = position + edit.insertion.length();
            else adjusted += edit.insertion.length() - edit.deleteCount;
            accumulated += edit.insertion.length() - edit.deleteCount;
        }
        return adjusted;
    }

    private static boolean opensBlock(String code) {
        if (code.isEmpty()) return false;
        if (code.matches(".*\\\\begin\\{[^}]+\\}\\s*")) return true;
        char last = code.charAt(code.length() - 1);
        return last == '{' || last == '[' || last == '(';
    }

    private static boolean startsWithCloser(String line) {
        return line.startsWith("\\end{") || line.startsWith("}")
                || line.startsWith("]") || line.startsWith(")");
    }

    private static String withoutComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '%') continue;
            int slashes = 0;
            for (int j = i - 1; j >= 0 && line.charAt(j) == '\\'; j--) slashes++;
            if ((slashes & 1) == 0) return line.substring(0, i);
        }
        return line;
    }

    private static String normalizedIndent(String line) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') out.append(' ');
            else if (c == '\t') out.append(UNIT);
            else break;
        }
        return out.toString();
    }

    private static int removableIndent(CharSequence text, int lineStart) {
        if (lineStart >= text.length()) return 0;
        if (text.charAt(lineStart) == '\t') return 1;
        int spaces = 0;
        while (lineStart + spaces < text.length() && spaces < WIDTH
                && text.charAt(lineStart + spaces) == ' ') spaces++;
        return spaces;
    }

    private static int lineStart(CharSequence text, int offset) {
        int at = Math.min(offset, text.length());
        while (at > 0 && text.charAt(at - 1) != '\n') at--;
        return at;
    }

    private static int indexOf(CharSequence text, char needle, int start) {
        for (int i = Math.max(0, start); i < text.length(); i++) {
            if (text.charAt(i) == needle) return i;
        }
        return -1;
    }

    public static final class Expansion {
        public final String insertion;
        public final int caretInInsertion;

        Expansion(String insertion, int caretInInsertion) {
            this.insertion = insertion;
            this.caretInInsertion = caretInInsertion;
        }
    }

    public static final class LineEdit {
        public final int start;
        public final int deleteCount;
        public final String insertion;

        LineEdit(int start, int deleteCount, String insertion) {
            this.start = start;
            this.deleteCount = deleteCount;
            this.insertion = insertion;
        }
    }
}
