package com.ccs.shard.block;

import java.util.ArrayList;
import java.util.List;

/**
 * A pipe-table: a header row plus body rows, and a per-column alignment.
 *
 * <p>Stored as plain strings; inline Markdown inside a cell is rendered by the
 * cell view, not parsed here. Rows are kept rectangular at all times so the grid
 * view never has to guard against ragged data.
 */
public final class TableData {

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    /** Row 0 is the header. */
    private final List<List<String>> rows = new ArrayList<>();
    private final List<Integer> alignments = new ArrayList<>();

    public TableData() {}

    public static TableData empty(int columns, int bodyRows) {
        TableData table = new TableData();
        List<String> header = new ArrayList<>(columns);
        for (int c = 0; c < columns; c++) header.add("");
        table.rows.add(header);
        for (int r = 0; r < bodyRows; r++) {
            List<String> row = new ArrayList<>(columns);
            for (int c = 0; c < columns; c++) row.add("");
            table.rows.add(row);
        }
        for (int c = 0; c < columns; c++) table.alignments.add(ALIGN_LEFT);
        return table;
    }

    public int rowCount() { return rows.size(); }

    public int columnCount() { return rows.isEmpty() ? 0 : rows.get(0).size(); }

    public String cell(int row, int column) {
        if (row < 0 || row >= rows.size()) return "";
        List<String> r = rows.get(row);
        if (column < 0 || column >= r.size()) return "";
        String value = r.get(column);
        return value == null ? "" : value;
    }

    public void setCell(int row, int column, String value) {
        if (row < 0 || row >= rows.size()) return;
        List<String> r = rows.get(row);
        while (r.size() <= column) r.add("");
        r.set(column, value == null ? "" : value);
    }

    public int alignment(int column) {
        if (column < 0 || column >= alignments.size()) return ALIGN_LEFT;
        Integer a = alignments.get(column);
        return a == null ? ALIGN_LEFT : a;
    }

    public void setAlignment(int column, int alignment) {
        while (alignments.size() <= column) alignments.add(ALIGN_LEFT);
        if (column >= 0) alignments.set(column, alignment);
    }

    // ---------------------------------------------------------------- structure

    public void addRow(int at) {
        int columns = Math.max(1, columnCount());
        List<String> row = new ArrayList<>(columns);
        for (int c = 0; c < columns; c++) row.add("");
        int index = Math.max(1, Math.min(at, rows.size()));
        rows.add(index, row);
    }

    public void removeRow(int at) {
        // The header is structural; a table always keeps at least one body row.
        if (at <= 0 || at >= rows.size() || rows.size() <= 2) return;
        rows.remove(at);
    }

    public void addColumn(int at) {
        int index = Math.max(0, Math.min(at, columnCount()));
        for (List<String> row : rows) {
            while (row.size() < index) row.add("");
            row.add(index, "");
        }
        while (alignments.size() < index) alignments.add(ALIGN_LEFT);
        alignments.add(index, ALIGN_LEFT);
    }

    public void removeColumn(int at) {
        if (columnCount() <= 1 || at < 0 || at >= columnCount()) return;
        for (List<String> row : rows) {
            if (at < row.size()) row.remove(at);
        }
        if (at < alignments.size()) alignments.remove(at);
    }

    public void moveRow(int from, int to) {
        if (from <= 0 || to <= 0 || from >= rows.size() || to >= rows.size()) return;
        rows.add(to, rows.remove(from));
    }

    public void moveColumn(int from, int to) {
        int columns = columnCount();
        if (from < 0 || to < 0 || from >= columns || to >= columns) return;
        for (List<String> row : rows) {
            if (from < row.size() && to < row.size()) row.add(to, row.remove(from));
        }
        if (from < alignments.size() && to < alignments.size()) {
            alignments.add(to, alignments.remove(from));
        }
    }

    public TableData copy() {
        TableData copy = new TableData();
        for (List<String> row : rows) copy.rows.add(new ArrayList<>(row));
        copy.alignments.addAll(alignments);
        return copy;
    }

    public boolean isEmpty() {
        for (List<String> row : rows) {
            for (String cell : row) {
                if (cell != null && !cell.trim().isEmpty()) return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- markdown

    /**
     * Renders as a GitHub-flavoured pipe table with columns padded to a common
     * width, so the raw Markdown stays readable in any other editor.
     */
    public String toMarkdown() {
        int columns = columnCount();
        if (columns == 0) return "";
        int[] widths = new int[columns];
        for (int c = 0; c < columns; c++) {
            widths[c] = 3;
            for (int r = 0; r < rows.size(); r++) {
                widths[c] = Math.max(widths[c], escape(cell(r, c)).length());
            }
            widths[c] = Math.min(widths[c], 40);
        }

        StringBuilder sb = new StringBuilder();
        appendRow(sb, 0, widths);
        sb.append('|');
        for (int c = 0; c < columns; c++) {
            int align = alignment(c);
            int width = Math.max(3, widths[c]);
            sb.append(' ');
            sb.append(align == ALIGN_CENTER || align == ALIGN_LEFT ? ':' : '-');
            for (int i = 0; i < width - 2; i++) sb.append('-');
            sb.append(align == ALIGN_CENTER || align == ALIGN_RIGHT ? ':' : '-');
            sb.append(" |");
        }
        sb.append('\n');
        for (int r = 1; r < rows.size(); r++) appendRow(sb, r, widths);
        // Trim the trailing newline; the serializer adds block separators.
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, int row, int[] widths) {
        sb.append('|');
        for (int c = 0; c < widths.length; c++) {
            String value = escape(cell(row, c));
            sb.append(' ').append(value);
            for (int i = value.length(); i < widths[c]; i++) sb.append(' ');
            sb.append(" |");
        }
        sb.append('\n');
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\n", " ");
    }

    /** Parses the pipe-table lines starting at {@code lines[from]}. */
    public static TableData parse(List<String> lines) {
        TableData table = new TableData();
        if (lines.isEmpty()) return empty(2, 1);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 1 && isDelimiterRow(line)) {
                List<String> cells = splitRow(line);
                for (int c = 0; c < cells.size(); c++) {
                    String spec = cells.get(c).trim();
                    boolean left = spec.startsWith(":");
                    boolean right = spec.endsWith(":");
                    table.setAlignment(c, left && right ? ALIGN_CENTER
                            : right ? ALIGN_RIGHT : ALIGN_LEFT);
                }
                continue;
            }
            table.rows.add(splitRow(line));
        }
        if (table.rows.isEmpty()) return empty(2, 1);
        // Normalise to a rectangle.
        int columns = 0;
        for (List<String> row : table.rows) columns = Math.max(columns, row.size());
        if (columns == 0) columns = 1;
        for (List<String> row : table.rows) {
            while (row.size() < columns) row.add("");
        }
        while (table.alignments.size() < columns) table.alignments.add(ALIGN_LEFT);
        if (table.rows.size() == 1) table.addRow(1);
        return table;
    }

    public static boolean isTableLine(String line) {
        String t = line.trim();
        return t.length() > 1 && t.startsWith("|");
    }

    public static boolean isDelimiterRow(String line) {
        String t = line.trim();
        if (!t.startsWith("|")) return false;
        boolean sawDash = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '-') sawDash = true;
            else if (c != '|' && c != ':' && c != ' ') return false;
        }
        return sawDash;
    }

    private static List<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        List<String> out = new ArrayList<>(4);
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                out.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString().trim());
        return out;
    }
}
