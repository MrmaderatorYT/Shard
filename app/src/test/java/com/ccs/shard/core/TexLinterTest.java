package com.ccs.shard.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.ccs.shard.R;

import org.junit.Test;

import java.util.List;

/**
 * The linter's value depends entirely on it not crying wolf, so the "clean
 * source produces nothing" cases matter as much as the detections.
 */
public class TexLinterTest {

    private static List<TexLinter.Problem> lint(String source) {
        return TexLinter.lint(source);
    }

    @Test
    public void wellFormedDocumentHasNoProblems() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\section{Intro}\n"
                + "Some text with $x^2 + y^2 = z^2$ inline math.\n"
                + "\\begin{itemize}\n"
                + "  \\item First\n"
                + "  \\item Second\n"
                + "\\end{itemize}\n"
                + "\\end{document}\n";
        assertEquals(0, lint(source).size());
    }

    @Test
    public void reportsUnclosedBrace() {
        List<TexLinter.Problem> problems = lint("\\textbf{bold");
        assertEquals(1, problems.size());
        assertEquals(R.string.lint_unclosed_brace, problems.get(0).messageRes);
        assertEquals(7, problems.get(0).start);
    }

    @Test
    public void reportsUnmatchedClosingBrace() {
        List<TexLinter.Problem> problems = lint("text}");
        assertEquals(1, problems.size());
        assertEquals(R.string.lint_unexpected_brace, problems.get(0).messageRes);
    }

    @Test
    public void reportsUnclosedEnvironment() {
        List<TexLinter.Problem> problems = lint("\\begin{itemize}\n\\item a\n");
        assertEquals(1, problems.size());
        assertEquals(R.string.lint_unclosed_environment, problems.get(0).messageRes);
        assertEquals("itemize", problems.get(0).argument);
    }

    @Test
    public void reportsMismatchedEnd() {
        List<TexLinter.Problem> problems = lint("\\begin{itemize}\n\\end{enumerate}\n");
        assertEquals(1, problems.size());
        assertEquals(R.string.lint_mismatched_end, problems.get(0).messageRes);
        assertEquals("itemize", problems.get(0).argument);
    }

    @Test
    public void reportsEndWithoutBegin() {
        List<TexLinter.Problem> problems = lint("\\end{itemize}\n");
        assertEquals(1, problems.size());
        assertEquals(R.string.lint_unexpected_end, problems.get(0).messageRes);
    }

    @Test
    public void reportsUnclosedInlineMath() {
        List<TexLinter.Problem> problems = lint("cost is $5 per unit\n");
        assertEquals(1, problems.size());
        assertEquals(R.string.lint_unclosed_math, problems.get(0).messageRes);
    }

    @Test
    public void displayMathPairsCorrectly() {
        assertEquals(0, lint("$$a = b$$").size());
    }

    @Test
    public void escapedCharactersAreNotSyntax() {
        // \{ \} \$ \% are literals and must not affect any counter.
        assertEquals(0, lint("100\\% of \\{braces\\} cost \\$5").size());
    }

    @Test
    public void commentsAreIgnored() {
        assertEquals(0, lint("text % an unbalanced { brace $ in a comment\n").size());
    }

    @Test
    public void nestedEnvironmentsPairCorrectly() {
        String source = "\\begin{a}\\begin{b}\\end{b}\\end{a}";
        assertEquals(0, lint(source).size());
    }

    @Test
    public void squareBracketsAreDeliberatelyNotChecked() {
        // Prose legitimately contains lone brackets; flagging them would train the
        // user to ignore every squiggle.
        assertEquals(0, lint("see [1 and also ] here").size());
    }

    @Test
    public void emptyAndNullSourceAreSafe() {
        assertEquals(0, lint("").size());
        assertEquals(0, TexLinter.lint(null).size());
    }

    @Test
    public void problemCountIsBounded() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 500; i++) source.append('{');
        assertTrue("must cap runaway reports", lint(source.toString()).size() <= 60);
    }
}
