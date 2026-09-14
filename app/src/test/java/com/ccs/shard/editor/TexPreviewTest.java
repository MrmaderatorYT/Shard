package com.ccs.shard.editor;

import android.text.Spanned;

import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TexPreviewTest {

    @Test
    public void suppliedThesisFixtureRendersWithoutKnownLeaks() throws Exception {
        String fixturePath = System.getenv("SHARD_TEX_FIXTURE");
        Assume.assumeTrue(fixturePath != null && !fixturePath.isEmpty());
        String source = new String(Files.readAllBytes(Paths.get(fixturePath)),
                StandardCharsets.UTF_8);

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        assertFalse(text.contains("[1em]"));
        assertFalse(text.contains("[1.5em]"));
        assertFalse(text.contains("sectionРЕФЕРАТ"));
        assertFalse(text.contains("LastPage"));
        assertFalse(text.contains("tab:economic-schools"));
        assertFalse(text.contains("2pt 1.181.18"));
        assertTrue(text.contains("Кваліфікаційна робота:"));
        assertTrue(text.contains("Порівняльна характеристика основних економічних шкіл"));
        assertTrue(text.indexOf(TexPreview.PAGE_BREAK_MARKER) >= 0);
        assertTrue(text.indexOf(TexPreview.LAST_PAGE_MARKER) >= 0);
        assertTrue(text.indexOf(TexPreview.LANDSCAPE_START_MARKER) >= 0);
        assertTrue(text.indexOf(TexPreview.LANDSCAPE_END_MARKER) >= 0);
        assertTrue(text.indexOf(TexPreview.LONGTABLE_REPEAT_HEADER_MARKER) >= 0);
        assertTrue(text.indexOf(TexPreview.LONGTABLE_END_MARKER) >= 0);
        assertEquals(1, occurrences(text, "Економічна школа"));
        assertFalse(text.contains("Продовження таблиці"));
        assertFalse(text.contains("Продовження на наступній сторінці"));
        assertTrue(text.contains("Джерело: складено за матеріалами"));
        assertTrue(text.contains("СПИСОК ВИКОРИСТАНИХ ДЖЕРЕЛ"));

        TexPreview.LongtableRepeatHeaderSpan[] repeatHeaders = ((Spanned) rendered).getSpans(
                0, rendered.length(), TexPreview.LongtableRepeatHeaderSpan.class);
        assertEquals(1, repeatHeaders.length);
        assertTrue(repeatHeaders[0].getHeader().toString().contains("Продовження таблиці"));
        assertTrue(repeatHeaders[0].getHeader().toString().contains("Економічна школа"));
        assertFalse(repeatHeaders[0].repeatsOnFirstPage());

        int titleEnd = text.indexOf(TexPreview.PAGE_BREAK_MARKER);
        String titlePage = text.substring(0, titleEnd);
        assertTrue(occurrences(titlePage, "\uFFFC") >= 6);
        assertTrue("title page gained duplicate blank paragraphs",
                titlePage.split("\\n", -1).length <= 28);
    }

    @Test
    public void thesisMarkupDoesNotLeakCommandsAndKeepsStructure() {
        String source = "\\documentclass{article}\n"
                + "\\usepackage{amsmath}\n"
                + "\\numberwithin{table}{section}\n"
                + "\\newcommand{\\sectionbreak}{\\clearpage}\n"
                + "\\newcommand{\\structsection}[1]{%\n"
                + "  \\clearpage\n"
                + "  \\phantomsection\n"
                + "  \\addcontentsline{toc}{section}{#1}%\n"
                + "  \\begin{center}\\textbf{\\MakeUppercase{#1}}\\end{center}\n"
                + "}\n"
                + "\\newcommand{\\studentName}{Пархоменко Дмитро}\n"
                + "\\begin{document}\n"
                + "\\begin{titlepage}\n"
                + "\\begin{center}\n"
                + "\\textbf{КВАЛІФІКАЦІЙНА РОБОТА}\\\\[1em]\n"
                + "\\studentName\n"
                + "\\end{center}\n"
                + "\\end{titlepage}\n"
                + "\\setcounter{page}{2}\n"
                + "\\structsection{РЕФЕРАТ}\n"
                + "Кваліфікаційна робота: \\pageref{LastPage}~с.\n"
                + "\\section{Розділ}\n"
                + "Див. таблицю~\\ref{tab:economic-schools}.\n"
                + "\\setlength{\\tabcolsep}{2pt}\n"
                + "\\renewcommand{\\arraystretch}{1.18}\n"
                + "\\begin{longtable}{lll}\n"
                + "\\caption{Порівняльна таблиця}\\label{tab:economic-schools}\\\\\n"
                + "Школа &\n"
                + "Люди &\n"
                + "Світ \\\\\n"
                + "Класична &\n"
                + "раціональні &\n"
                + "визначений \\\\\n"
                + "\\end{longtable}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        assertFalse(text.contains("[1em]"));
        assertFalse(text.contains("sectionРЕФЕРАТ"));
        assertFalse(text.contains("LastPage"));
        assertFalse(text.contains("tab:economic-schools"));
        assertFalse(text.contains("2pt"));
        assertFalse(text.contains("1.181.18"));
        assertTrue(text.contains("РЕФЕРАТ"));
        assertTrue(text.contains("Див. таблицю\u00A01.1."));
        assertTrue(text.contains("Школа   │   Люди   │   Світ"));
        assertTrue(text.indexOf(TexPreview.PAGE_BREAK_MARKER) >= 0);
        assertTrue(text.indexOf(TexPreview.LAST_PAGE_MARKER) >= 0);
    }

    @Test
    public void expandsAllArgumentsAndFindsExactTitleCommand() {
        String source = "\\documentclass{article}\n"
                + "\\titleformat{\\section}{x}{y}{z}{q}\n"
                + "\\title{Правильний заголовок}\n"
                + "\\newcommand{\\pair}[2]{#1 / #2}\n"
                + "\\newcommand{\\greet}[2][Привіт]{#1, #2}\n"
                + "\\begin{document}\n"
                + "\\maketitle\n"
                + "\\pair{ліва}{права}\n"
                + "\\greet{світ}; \\greet[Вітаю]{друже}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        assertTrue(text.contains("Правильний заголовок"));
        assertTrue(text.contains("ліва / права"));
        assertTrue(text.contains("Привіт, світ; Вітаю, друже"));
        assertFalse(text.contains("#2"));
        assertFalse(text.contains("titleformat"));
    }

    @Test
    public void globalReferenceCountersDoNotResetAtSections() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\section{Перша}\n"
                + "\\begin{table}\n"
                + "\\caption{Один}\\label{tab:one}\n"
                + "\\end{table}\n"
                + "\\section{Друга}\n"
                + "\\begin{table}\n"
                + "\\caption{Два}\\label{tab:two}\n"
                + "\\end{table}\n"
                + "Посилання~\\ref{tab:two}.\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        assertTrue(text.contains("Посилання\u00A02."));
    }

    @Test
    public void optionalLongtableControlBlocksDoNotHideDataRows() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\begin{longtable}{ll}\n"
                + "Шапка A & Шапка B \\\\\n"
                + "\\endfirsthead\n"
                + "Повтор A & Повтор B \\\\\n"
                + "\\endhead\n"
                + "Дані A & Дані B \\\\\n"
                + "\\end{longtable}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        assertTrue(text.contains("Шапка A   │   Шапка B"));
        assertFalse(text.contains("Повтор A"));
        assertTrue(text.contains("Дані A   │   Дані B"));
        TexPreview.LongtableRepeatHeaderSpan[] headers = ((Spanned) rendered).getSpans(
                0, rendered.length(), TexPreview.LongtableRepeatHeaderSpan.class);
        assertEquals(1, headers.length);
        assertTrue(headers[0].getHeader().toString().contains("Повтор A"));
        assertFalse(headers[0].getHeader().toString().contains("Шапка A"));
        assertFalse(headers[0].repeatsOnFirstPage());
    }

    @Test
    public void endheadOnlyLongtableStillMarksItsRepeatingHeader() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\begin{longtable}{ll}\n"
                + "Шапка A & Шапка B \\\\\n"
                + "\\endhead\n"
                + "Дані A & Дані B \\\\\n"
                + "\\end{longtable}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        int headerStart = text.indexOf(TexPreview.LONGTABLE_HEADER_START_MARKER);
        int headerEnd = text.indexOf(TexPreview.LONGTABLE_HEADER_END_MARKER);
        int tableEnd = text.indexOf(TexPreview.LONGTABLE_END_MARKER);
        assertTrue(headerStart >= 0);
        assertTrue(headerEnd > headerStart);
        assertTrue(tableEnd > headerEnd);
        assertTrue(text.contains("Шапка A   │   Шапка B"));
        assertTrue(text.contains("Дані A   │   Дані B"));
        assertEquals(0, ((Spanned) rendered).getSpans(0, rendered.length(),
                TexPreview.LongtableRepeatHeaderSpan.class).length);
    }

    @Test
    public void endfirstheadWithoutEndheadDoesNotInventARepeatingHeader() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\begin{longtable}{ll}\n"
                + "Лише перша A & Лише перша B \\\\\n"
                + "\\endfirsthead\n"
                + "Дані A & Дані B \\\\\n"
                + "\\end{longtable}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        assertTrue(text.contains("Лише перша A   │   Лише перша B"));
        assertTrue(text.contains("Дані A   │   Дані B"));
        assertEquals(-1, text.indexOf(TexPreview.LONGTABLE_HEADER_START_MARKER));
        assertEquals(-1, text.indexOf(TexPreview.LONGTABLE_REPEAT_HEADER_MARKER));
        assertEquals(-1, text.indexOf(TexPreview.LONGTABLE_END_MARKER));
        assertEquals(0, ((Spanned) rendered).getSpans(0, rendered.length(),
                TexPreview.LongtableRepeatHeaderSpan.class).length);
    }

    @Test
    public void manualPageBreakKeepsContinuationHeaderProtocolComplete() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\begin{longtable}{ll}\n"
                + "Перша A & Перша B \\\\\n"
                + "\\endfirsthead\n"
                + "Повтор A & Повтор B \\\\\n"
                + "\\endhead\n"
                + "До A & До B \\\\\n"
                + "\\newpage\n"
                + "Після A & Після B \\\\\n"
                + "\\end{longtable}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        String text = rendered.toString();

        int pageBreak = text.indexOf(TexPreview.PAGE_BREAK_MARKER);
        assertTrue(pageBreak > 0);
        assertEquals(2, occurrences(text,
                String.valueOf(TexPreview.LONGTABLE_REPEAT_HEADER_MARKER)));
        assertEquals(2, occurrences(text, String.valueOf(TexPreview.LONGTABLE_END_MARKER)));
        assertTrue(text.lastIndexOf(TexPreview.LONGTABLE_END_MARKER, pageBreak) >= 0);
        assertTrue(text.indexOf(TexPreview.LONGTABLE_REPEAT_HEADER_MARKER, pageBreak) > pageBreak);
        assertTrue(text.contains("До A   │   До B"));
        assertTrue(text.contains("Після A   │   Після B"));

        TexPreview.LongtableRepeatHeaderSpan[] headers = ((Spanned) rendered).getSpans(
                0, rendered.length(), TexPreview.LongtableRepeatHeaderSpan.class);
        assertEquals(2, headers.length);
        assertFalse(headers[0].repeatsOnFirstPage());
        assertTrue(headers[1].repeatsOnFirstPage());
        assertTrue(headers[0].getHeader().toString().contains("Повтор A"));
        assertTrue(headers[1].getHeader().toString().contains("Повтор A"));
        assertFalse(headers[0].getHeader().toString().contains("Перша A"));
    }

    @Test
    public void equationBlockRendersWithEquationSpan() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\section{Математика}\n"
                + "\\begin{equation}\n"
                + "U_C = f(S, L, A) \\to \\max,\n"
                + "\\end{equation}\n"
                + "\\begin{equation}\n"
                + "R = \\frac{\\int_{0}^{T} A(t) \\cdot D(t)\\,dt}{T_{rec}},\n"
                + "\\end{equation}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        Spanned spanned = (Spanned) rendered;
        TexPreview.EquationSpan[] eqSpans = spanned.getSpans(
                0, spanned.length(), TexPreview.EquationSpan.class);
        assertEquals(2, eqSpans.length);
        assertTrue(rendered.toString().contains("(1.1)"));
        assertTrue(rendered.toString().contains("(1.2)"));
    }

    @Test
    public void tikzDiagramRendersWithTikzArchitectureSpan() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\begin{figure}[htbp]\n"
                + "\\begin{tikzpicture}\n"
                + "\\node {Hub};\n"
                + "\\end{tikzpicture}\n"
                + "\\caption{Порівняння архітектур}\n"
                + "\\end{figure}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        Spanned spanned = (Spanned) rendered;
        TexPreview.TikzFigureSpan[] spans = spanned.getSpans(
                0, spanned.length(), TexPreview.TikzFigureSpan.class);
        assertEquals(1, spans.length);
        assertTrue(rendered.toString().contains("Рисунок 1 – Порівняння архітектур"));
    }

    @Test
    public void compositeTikzFigureIsRenderedOnlyOnce() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\begin{figure}[htbp]\n"
                + "\\begin{tikzpicture}\n"
                + "\\node {Hub};\n"
                + "\\end{tikzpicture}\n"
                + "\\qquad\n"
                + "\\begin{tikzpicture}\n"
                + "\\node {Mesh};\n"
                + "\\end{tikzpicture}\n"
                + "\\caption{Порівняння двох архітектур}\n"
                + "\\end{figure}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        Spanned spanned = (Spanned) rendered;
        TexPreview.TikzFigureSpan[] spans = spanned.getSpans(
                0, spanned.length(), TexPreview.TikzFigureSpan.class);
        assertEquals(1, spans.length);
        assertEquals(1, occurrences(rendered.toString(), "Рисунок 1 –"));
    }

    @Test
    public void tableOfContentsContainsTocRowSpans() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "\\tableofcontents\n"
                + "\\section{Огляд}\n"
                + "\\subsection{Передумови}\n"
                + "\\newpage\n"
                + "\\section{Висновки}\n"
                + "\\end{document}";

        CharSequence rendered = new TexPreview(new TexPreview.Palette()).render(source);
        Spanned spanned = (Spanned) rendered;
        TexPreview.TocRowSpan[] spans = spanned.getSpans(
                0, spanned.length(), TexPreview.TocRowSpan.class);
        assertEquals(3, spans.length);
        assertTrue(rendered.toString().contains("ЗМІСТ"));
        assertTrue(rendered.toString().contains("Огляд"));
        assertTrue(rendered.toString().contains("Передумови"));
        assertTrue(rendered.toString().contains("Висновки"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
