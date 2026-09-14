package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.List;

/** Finds the TeX delimiter pair next to the caret without Android dependencies. */
public final class TexPairMatcher {

    private TexPairMatcher() {}

    public static Pair find(CharSequence text, int cursor) {
        if (text == null || cursor < 0 || cursor > text.length() || text.length() == 0) {
            return null;
        }
        int candidate = -1;
        if (cursor > 0 && isDelimiter(text.charAt(cursor - 1))) candidate = cursor - 1;
        else if (cursor < text.length() && isDelimiter(text.charAt(cursor))) candidate = cursor;
        if (candidate < 0 || isEscaped(text, candidate)) return null;

        char value = text.charAt(candidate);
        if (value == '$') return findDollarPair(text, candidate);
        return findBracketPair(text, candidate, value);
    }

    private static final int MAX_LOOKAHEAD = 2048;

    private static Pair findBracketPair(CharSequence text, int candidate, char value) {
        char open = openingFor(value);
        char close = closingFor(value);
        boolean forward = value == open;
        int depth = 0;
        if (forward) {
            int limit = Math.min(text.length(), candidate + MAX_LOOKAHEAD);
            for (int i = candidate; i < limit; i++) {
                if (isEscaped(text, i)) continue;
                char c = text.charAt(i);
                if (c == open) depth++;
                else if (c == close && --depth == 0) return new Pair(candidate, 1, i, 1);
            }
        } else {
            int limit = Math.max(0, candidate - MAX_LOOKAHEAD);
            for (int i = candidate; i >= limit; i--) {
                if (isEscaped(text, i)) continue;
                char c = text.charAt(i);
                if (c == close) depth++;
                else if (c == open && --depth == 0) return new Pair(i, 1, candidate, 1);
            }
        }
        return null;
    }

    private static Pair findDollarPair(CharSequence text, int candidate) {
        int tokenStart = candidate;
        if (candidate > 0 && text.charAt(candidate - 1) == '$'
                && !isEscaped(text, candidate - 1)) {
            tokenStart = candidate - 1;
        }
        int tokenLength = tokenStart + 1 < text.length()
                && text.charAt(tokenStart + 1) == '$' ? 2 : 1;

        int windowStart = Math.max(0, tokenStart - MAX_LOOKAHEAD);
        int windowEnd = Math.min(text.length(), tokenStart + MAX_LOOKAHEAD);

        List<Integer> tokens = new ArrayList<>();
        for (int i = windowStart; i < windowEnd;) {
            if (text.charAt(i) != '$' || isEscaped(text, i)) {
                i++;
                continue;
            }
            boolean doubled = i + 1 < text.length() && text.charAt(i + 1) == '$';
            int length = doubled ? 2 : 1;
            if (length == tokenLength) tokens.add(i);
            i += length;
        }
        int index = tokens.indexOf(tokenStart);
        if (index < 0) return null;
        int other = (index & 1) == 0 ? index + 1 : index - 1;
        if (other < 0 || other >= tokens.size()) return null;
        int first = Math.min(tokenStart, tokens.get(other));
        int second = Math.max(tokenStart, tokens.get(other));
        return new Pair(first, tokenLength, second, tokenLength);
    }

    private static boolean isDelimiter(char c) {
        return c == '{' || c == '}' || c == '[' || c == ']'
                || c == '(' || c == ')' || c == '$';
    }

    private static char openingFor(char c) {
        if (c == '{' || c == '}') return '{';
        if (c == '[' || c == ']') return '[';
        return '(';
    }

    private static char closingFor(char c) {
        if (c == '{' || c == '}') return '}';
        if (c == '[' || c == ']') return ']';
        return ')';
    }

    private static boolean isEscaped(CharSequence text, int position) {
        int slashes = 0;
        for (int i = position - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) == 1;
    }

    public static final class Pair {
        public final int firstStart;
        public final int firstLength;
        public final int secondStart;
        public final int secondLength;

        Pair(int firstStart, int firstLength, int secondStart, int secondLength) {
            this.firstStart = firstStart;
            this.firstLength = firstLength;
            this.secondStart = secondStart;
            this.secondLength = secondLength;
        }
    }
}
