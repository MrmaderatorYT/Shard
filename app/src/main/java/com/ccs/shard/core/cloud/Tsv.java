package com.ccs.shard.core.cloud;

/**
 * Column escaping for the cloud sidecar files, matching
 * {@link com.ccs.shard.core.NoteIndex}'s scheme so the formats stay readable
 * with the same eye. Vault paths can legitimately contain almost anything, so
 * unlike {@code FileTreeSync} - which replaces tabs with underscores and loses
 * information - this round-trips exactly.
 */
final class Tsv {

    private Tsv() {}

    static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unesc(String s) {
        if (s == null) return "";
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                if (next == 't') sb.append('\t');
                else if (next == 'n') sb.append('\n');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }

    static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }
}
