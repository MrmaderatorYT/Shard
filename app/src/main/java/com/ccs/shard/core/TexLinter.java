package com.ccs.shard.core;

import com.ccs.shard.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the structural mistakes that actually stop a TeX document compiling.
 *
 * <p>Deliberately narrow. Three rules cover most real breakage and — more
 * importantly — never fire on correct source:
 *
 * <ul>
 *   <li>unbalanced <code>{}</code>,</li>
 *   <li><code>\begin{x}</code> without a matching <code>\end{x}</code>, or an
 *       <code>\end</code> that closes the wrong environment,</li>
 *   <li>an unclosed <code>$</code> or <code>$$</code> math delimiter.</li>
 * </ul>
 *
 * <p><code>[]</code> is intentionally not checked: square brackets appear
 * unpaired in ordinary prose often enough that flagging them would train the
 * user to ignore the squiggles.
 *
 * <p>Comments and escaped characters are skipped, so <code>\%</code> and
 * <code>\{</code> are never mistaken for syntax.
 */
public final class TexLinter {

    /** How many problems are worth marking before the document is just red. */
    private static final int MAX_PROBLEMS = 60;

    /** One diagnostic, as a character range plus a localisable message. */
    public static final class Problem {
        public final int start;
        public final int end;
        public final int messageRes;
        /** Environment name for environment problems, else null. */
        public final String argument;

        Problem(int start, int end, int messageRes, String argument) {
            this.start = start;
            this.end = end;
            this.messageRes = messageRes;
            this.argument = argument;
        }
    }

    private TexLinter() {}

    public static List<Problem> lint(CharSequence source) {
        List<Problem> problems = new ArrayList<>();
        if (source == null || source.length() == 0) return problems;

        List<Integer> braces = new ArrayList<>();
        List<int[]> environments = new ArrayList<>();  // {nameStart, nameEnd}
        List<String> environmentNames = new ArrayList<>();
        int mathStart = -1;
        int mathLength = 0;  // 1 for $, 2 for $$

        int i = 0;
        int length = source.length();
        while (i < length && problems.size() < MAX_PROBLEMS) {
            char c = source.charAt(i);

            // An escaped character is literal — except a control word, which may be
            // \begin or \end and has to be read.
            if (c == '\\' && i + 1 < length) {
                char next = source.charAt(i + 1);
                if (!Character.isLetter(next)) {
                    i += 2;
                    continue;
                }
                int wordEnd = i + 1;
                while (wordEnd < length && Character.isLetter(source.charAt(wordEnd))) wordEnd++;
                String word = source.subSequence(i + 1, wordEnd).toString();
                if (word.equals("begin") || word.equals("end")) {
                    int[] name = readBracedName(source, wordEnd);
                    if (name != null) {
                        String environment = source.subSequence(name[0], name[1]).toString();
                        if (word.equals("begin")) {
                            environments.add(new int[]{name[0], name[1]});
                            environmentNames.add(environment);
                        } else {
                            closeEnvironment(problems, environments, environmentNames,
                                    environment, name[0], name[1]);
                        }
                        i = name[2];
                        continue;
                    }
                }
                i = wordEnd;
                continue;
            }

            if (c == '%') {
                // Comment: everything to the end of the line is not source.
                while (i < length && source.charAt(i) != '\n') i++;
                continue;
            }

            if (c == '{') {
                braces.add(i);
                i++;
                continue;
            }
            if (c == '}') {
                if (braces.isEmpty()) {
                    problems.add(new Problem(i, i + 1, R.string.lint_unexpected_brace, null));
                } else {
                    braces.remove(braces.size() - 1);
                }
                i++;
                continue;
            }

            if (c == '$') {
                int run = (i + 1 < length && source.charAt(i + 1) == '$') ? 2 : 1;
                if (mathStart < 0) {
                    mathStart = i;
                    mathLength = run;
                } else if (mathLength == run) {
                    mathStart = -1;
                    mathLength = 0;
                }
                // A mismatched run ($ closing $$) is left open on purpose: the
                // eventual "unclosed" report points at the real opener.
                i += run;
                continue;
            }
            i++;
        }

        for (int start : braces) {
            if (problems.size() >= MAX_PROBLEMS) break;
            problems.add(new Problem(start, start + 1, R.string.lint_unclosed_brace, null));
        }
        for (int e = 0; e < environments.size() && problems.size() < MAX_PROBLEMS; e++) {
            int[] range = environments.get(e);
            problems.add(new Problem(range[0], range[1],
                    R.string.lint_unclosed_environment, environmentNames.get(e)));
        }
        if (mathStart >= 0 && problems.size() < MAX_PROBLEMS) {
            problems.add(new Problem(mathStart, mathStart + mathLength,
                    R.string.lint_unclosed_math, null));
        }
        return problems;
    }

    /** Matches an {@code \end{x}} against the innermost open environment. */
    private static void closeEnvironment(List<Problem> problems, List<int[]> open,
                                         List<String> names, String environment,
                                         int nameStart, int nameEnd) {
        if (open.isEmpty()) {
            problems.add(new Problem(nameStart, nameEnd,
                    R.string.lint_unexpected_end, environment));
            return;
        }
        int last = open.size() - 1;
        if (names.get(last).equals(environment)) {
            open.remove(last);
            names.remove(last);
            return;
        }
        // Closing the wrong environment: report it here and unwind, so one typo
        // does not cascade into a problem on every enclosing \begin.
        problems.add(new Problem(nameStart, nameEnd,
                R.string.lint_mismatched_end, names.get(last)));
        open.remove(last);
        names.remove(last);
    }

    /**
     * Reads {@code {name}} directly after a control word.
     *
     * @return {@code {nameStart, nameEnd, indexAfterBrace}} or null
     */
    private static int[] readBracedName(CharSequence source, int from) {
        int i = from;
        int length = source.length();
        while (i < length && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) i++;
        if (i >= length || source.charAt(i) != '{') return null;
        int nameStart = i + 1;
        int nameEnd = nameStart;
        while (nameEnd < length && source.charAt(nameEnd) != '}'
                && source.charAt(nameEnd) != '\n') {
            nameEnd++;
        }
        if (nameEnd >= length || source.charAt(nameEnd) != '}') return null;
        return new int[]{nameStart, nameEnd, nameEnd + 1};
    }
}
