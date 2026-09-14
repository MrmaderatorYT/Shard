package com.ccs.shard;

import com.ccs.shard.block.BlockDocument;
import com.ccs.shard.block.InlineMd;
import com.ccs.shard.core.CalendarStats;
import com.ccs.shard.core.Md;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteFile;
import com.ccs.shard.core.NoteProperties;
import com.ccs.shard.core.TaskDetails;
import com.ccs.shard.core.TaskItem;
import com.ccs.shard.core.TaskMetadata;
import com.ccs.shard.core.TexAutocomplete;
import com.ccs.shard.core.TexIndentation;
import com.ccs.shard.core.TexLinter;
import com.ccs.shard.core.TexPairMatcher;
import com.ccs.shard.editor.TexPreview;
import com.ccs.shard.editor.codeHighliter.LanguageDetector;
import com.ccs.shard.editor.codeHighliter.Tex;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void yamlProperties_roundTripWithoutTouchingBody() {
        Note note = NoteFile.parse("A body with #tag", "Ideas.md", 100L);
        assertTrue(NoteProperties.put(note, "status", "in progress"));
        assertTrue(NoteProperties.put(note, "priority", "high"));

        Note restored = NoteFile.parse(NoteFile.serialize(note), "Ideas.md", 200L);
        Map<String, String> properties = NoteProperties.read(restored);
        assertEquals("in progress", properties.get("status"));
        assertEquals("high", properties.get("priority"));
        assertEquals("A body with #tag", restored.getContent());
        assertTrue(restored.hasTag("tag"));
    }

    @Test
    public void yamlProperties_doNotOverrideShardMetadata() {
        Note note = new Note("x.md", "x");
        note.setContent("");
        assertFalse(NoteProperties.put(note, "created", "tomorrow"));
        assertFalse(NoteProperties.put(note, "bad key", "value"));
        assertTrue(NoteProperties.read(note).isEmpty());
    }

    @Test
    public void bulkTagEditing_isPortableAndSkipsFencedCode() {
        String source = "Body #old\n\n```java\n#insideCode\n```";
        String added = Md.withInlineTag(source, "#new", true);
        assertTrue(added.contains("#new"));
        assertEquals(added, Md.withInlineTag(added, "new", true));

        String removed = Md.withInlineTag(added, "old", false);
        assertFalse(removed.contains("#old"));
        assertTrue(removed.contains("#insideCode"));
        assertEquals(removed, Md.withInlineTag(removed, "insideCode", false));
        assertEquals("project/tag", Md.normalizeTag("#project/tag"));
        assertEquals("", Md.normalizeTag("not a valid tag"));
    }

    @Test
    public void calendarStats_groupNotesTasksAndActivityByLocalDay() {
        Calendar created = Calendar.getInstance();
        created.clear();
        created.set(2030, Calendar.MARCH, 5, 10, 0, 0);
        Calendar edited = (Calendar) created.clone();
        edited.add(Calendar.DAY_OF_MONTH, 1);

        Note note = new Note("Ideas.md", "Ideas");
        note.setCreatedMillis(created.getTimeInMillis());
        note.setModifiedMillis(edited.getTimeInMillis());

        long due = TaskMetadata.startOfDay(created.getTimeInMillis());
        List<TaskItem> tasks = new ArrayList<>();
        tasks.add(new TaskItem(note.getId(), note.getTitle(), "open", 0, false, due));
        tasks.add(new TaskItem(note.getId(), note.getTitle(), "done", 1, true, due));
        tasks.add(new TaskItem("Daily/2030-03-05.md", "2030-03-05",
                "daily", 0, false, 0));

        List<CalendarStats.Day> days = CalendarStats.build(
                2030, Calendar.MARCH, java.util.Collections.singletonList(note), tasks);
        assertEquals(42, days.size());
        CalendarStats.Day march5 = findDay(days, 5);
        CalendarStats.Day march6 = findDay(days, 6);
        assertEquals(1, march5.noteCount);
        assertEquals(3, march5.taskCount);
        assertEquals(2, march5.openTaskCount);
        assertEquals(1, march6.activityCount);
    }

    private static CalendarStats.Day findDay(List<CalendarStats.Day> days, int dayOfMonth) {
        for (CalendarStats.Day day : days) {
            if (day.inMonth && day.dayOfMonth == dayOfMonth) return day;
        }
        throw new AssertionError("day not found: " + dayOfMonth);
    }

    @Test
    public void taskDeadline_staysPortableMarkdown() {
        String markdown = "- [ ] Ship release 📅 2030-04-05\n- [x] Done";
        BlockDocument document = BlockDocument.parse(markdown);
        assertEquals(markdown, document.toMarkdown().trim());
        assertTrue(TaskMetadata.parseDay("2030-04-05") > 0);
        assertEquals(0, TaskMetadata.parseDay("2030-99-99"));
    }

    @Test
    public void taskMetadata_roundTripsAsPortableInlineMarkdown() {
        String task = "Prepare release @priority(high) 📅 2030-04-05 "
                + "@time(14:30) @remind(13:45) @repeat(weekly)";
        TaskDetails details = TaskMetadata.parse(task);

        assertEquals("Prepare release", details.cleanText);
        assertEquals(TaskItem.PRIORITY_HIGH, details.priority);
        assertEquals(TaskMetadata.parseDay("2030-04-05"), details.dueMillis);
        assertEquals(14 * 60 + 30, details.dueTimeMinutes);
        assertEquals(13 * 60 + 45, details.reminderMinutes);
        assertEquals(TaskItem.Repeat.WEEKLY, details.repeat);

        String restored = TaskMetadata.write(task, details);
        assertTrue(restored.startsWith("Prepare release "));
        assertTrue(restored.contains("@priority(high)"));
        assertTrue(restored.contains("📅 2030-04-05"));
        assertTrue(restored.contains("@time(14:30)"));
        assertTrue(restored.contains("@remind(13:45)"));
        assertTrue(restored.contains("@repeat(weekly)"));
    }

    @Test
    public void archivedTaskMetadata_roundTripsAndIsNotPartOfVisibleText() {
        TaskDetails details = TaskMetadata.parse(
                "Keep for later #someday @archived");

        assertTrue(details.archived);
        assertEquals("Keep for later #someday", details.cleanText);
        assertTrue(TaskMetadata.write(details.cleanText, details).contains("@archived"));
    }

    @Test
    public void recurringTask_movesToTheNextOccurrenceInsteadOfStayingCompleted() {
        String task = "Stand-up 📅 2030-04-05 @repeat(weekly)";
        String advanced = TaskMetadata.advanceRecurring(task);

        assertTrue(advanced.startsWith("Stand-up "));
        assertTrue(advanced.contains("📅 2030-04-12"));
        assertTrue(advanced.contains("@repeat(weekly)"));
    }

    @Test
    public void softLineBreaks_roundTripInsideEveryTextBlock() {
        String markdown = "# Heading<br>continued\n\n"
                + "- list item<br>continued\n\n"
                + "paragraph\ncontinued";

        BlockDocument document = BlockDocument.parse(markdown);
        assertEquals("Heading\ncontinued", document.get(0).text);
        assertEquals("list item\ncontinued", document.get(1).text);
        assertEquals("paragraph\ncontinued", document.get(2).text);
        assertEquals(markdown, document.toMarkdown().trim());
    }

    @Test
    public void texSource_roundTripsWithoutMarkdownOrYamlConversion() {
        String source = "\\documentclass{article}\n"
                + "\\begin{document}\n"
                + "$E = mc^2$ % keep this comment\n"
                + "\\end{document}";

        assertTrue(NoteFile.isNoteFile("paper.tex"));
        assertTrue(NoteFile.isTexFile("folder/PAPER.TEX"));
        assertEquals("paper", NoteFile.titleFromPath("research/paper.tex"));

        Note note = NoteFile.parse(source, "research/paper.tex", 100L);
        assertEquals(source, note.getContent());
        assertEquals(source, NoteFile.serialize(note));

        note.setPinned(true);
        String stored = NoteFile.serialize(note);
        assertTrue(stored.startsWith("% !shard-metadata\n"));
        assertFalse(stored.startsWith("---"));

        Note restored = NoteFile.parse(stored, "research/paper.tex", 200L);
        assertTrue(restored.isPinned());
        assertEquals(source, restored.getContent());
    }

    @Test
    public void texAutocomplete_matchesPrefixAndPlacesCaretInsideSnippet() {
        String input = "Equation: \\fra";
        java.util.List<TexAutocomplete.Match> matches =
                TexAutocomplete.suggest(input, input.length(), 7);
        assertFalse(matches.isEmpty());
        TexAutocomplete.Match fraction = matches.get(0);
        assertEquals("\\frac{}{}", fraction.insertion);
        assertEquals(10, fraction.replaceStart);
        assertEquals("\\frac{".length(), fraction.caretInInsertion);

        String lineBreak = "line \\\\";
        assertTrue(TexAutocomplete.suggest(
                lineBreak, lineBreak.length(), 7).isEmpty());
        assertTrue(TexAutocomplete.suggest("\\frac{", 6, 7).isEmpty());
    }

    @Test
    public void texAutocomplete_containsExpandedLatexVocabulary() {
        assertSuggestion("\\Gam", "\\Gamma");
        assertSuggestion("\\tab", "\\tableofcontents");
        assertSuggestion("\\mathb", "\\mathbb{}");
        assertSuggestion("\\Righ", "\\Rightarrow");
        assertSuggestion("\\footn", "\\footnote{}");
        assertSuggestion("\\begin{tab", "\\begin{tabular}{}\n  \n\\end{tabular}");
    }

    @Test
    public void texAutocomplete_closesArbitraryEnvironment() {
        TexAutocomplete.EnvironmentExpansion expansion =
                TexAutocomplete.environmentExpansion("  \\begin{proof}", 15);
        assertNotNull(expansion);
        assertEquals("\n    \n  \\end{proof}", expansion.insertion);
        assertEquals(5, expansion.caretInInsertion);

        String complete = "\\begin{proof}\ntext\n\\end{proof}";
        assertNull(TexAutocomplete.environmentExpansion(complete, "\\begin{proof}".length()));
    }

    @Test
    public void texPairMatcher_matchesBracesAndMathDelimiters() {
        String inline = "$a + {b}$";
        TexPairMatcher.Pair braces = TexPairMatcher.find(inline, 6);
        assertNotNull(braces);
        assertEquals(5, braces.firstStart);
        assertEquals(7, braces.secondStart);

        TexPairMatcher.Pair dollars = TexPairMatcher.find(inline, 1);
        assertNotNull(dollars);
        assertEquals(0, dollars.firstStart);
        assertEquals(8, dollars.secondStart);

        TexPairMatcher.Pair display = TexPairMatcher.find("$$x$$", 2);
        assertNotNull(display);
        assertEquals(2, display.firstLength);
        assertEquals(3, display.secondStart);
    }

    @Test
    public void languageDetector_recognizesTexAndLatex() {
        assertTrue(LanguageDetector.forLanguage("tex") instanceof Tex);
        assertTrue(LanguageDetector.forLanguage("latex") instanceof Tex);
        assertEquals("tex", LanguageDetector.detectFromContent(
                "\\documentclass{article}\n\\begin{document}"));
    }

    @Test
    public void texIndentation_formatsEnterLikeAnIde() {
        String begin = "  \\begin{itemize}\n";
        TexIndentation.Expansion nested =
                TexIndentation.newlineExpansion(begin, begin.length());
        assertNotNull(nested);
        assertEquals("    ", nested.insertion);
        assertEquals(4, nested.caretInInsertion);

        String braces = "\\textbf{\n}";
        int cursor = braces.indexOf('\n') + 1;
        TexIndentation.Expansion split = TexIndentation.newlineExpansion(braces, cursor);
        assertNotNull(split);
        assertEquals("  \n", split.insertion);
        assertEquals(2, split.caretInInsertion);

        String commentedBrace = "  text % {\n";
        assertEquals("  ", TexIndentation.newlineExpansion(
                commentedBrace, commentedBrace.length()).insertion);
    }

    @Test
    public void texIndentation_handlesTabSelectionAndClosingLines() {
        assertEquals(" ", TexIndentation.tabSpaces("abc", 3));
        assertEquals("  ", TexIndentation.tabSpaces("abcd", 4));

        String source = "a\n  b";
        java.util.List<TexIndentation.LineEdit> indent =
                TexIndentation.lineEdits(source, 0, source.length(), false);
        assertEquals("  a\n    b", applyLineEdits(source, indent));

        java.util.List<TexIndentation.LineEdit> outdent =
                TexIndentation.lineEdits("  a\n    b", 0, 9, true);
        assertEquals("a\n  b", applyLineEdits("  a\n    b", outdent));

        TexIndentation.LineEdit closing =
                TexIndentation.closingLineEdit("    \\end{proof}", 15);
        assertNotNull(closing);
        assertEquals(2, closing.start);
        assertEquals(2, closing.deleteCount);
    }

    private static String applyLineEdits(String source,
                                         java.util.List<TexIndentation.LineEdit> edits) {
        StringBuilder out = new StringBuilder(source);
        for (int i = edits.size() - 1; i >= 0; i--) {
            TexIndentation.LineEdit edit = edits.get(i);
            out.replace(edit.start, edit.start + edit.deleteCount, edit.insertion);
        }
        return out.toString();
    }

    private static void assertSuggestion(String input, String expectedInsertion) {
        java.util.List<TexAutocomplete.Match> matches =
                TexAutocomplete.suggest(input, input.length(), 20);
        for (TexAutocomplete.Match match : matches) {
            if (expectedInsertion.equals(match.insertion)) return;
        }
        fail("Missing TeX suggestion " + expectedInsertion + " for " + input);
    }

    @Test
    public void testLarge50kWordsPerformance() {
        StringBuilder sb = new StringBuilder(400_000);
        for (int i = 0; i < 50_000; i++) {
            if (i % 10 == 0) {
                sb.append("word_with_underscore_").append(i).append(" ");
            } else if (i % 25 == 0) {
                sb.append("*emphasis* ");
            } else if (i % 50 == 0) {
                sb.append("`code_sample` ");
            } else if (i % 100 == 0) {
                sb.append("[[wikilink|alias]] ");
            } else {
                sb.append("word").append(i).append(" ");
            }
        }
        String largeSingleLine = sb.toString();

        long start = System.currentTimeMillis();
        InlineMd inlineMd = new InlineMd(new InlineMd.Palette());
        CharSequence rendered = inlineMd.render(largeSingleLine);
        CharSequence renderedRaw = inlineMd.renderRaw(largeSingleLine);
        long elapsedInline = System.currentTimeMillis() - start;

        assertNotNull(rendered);
        assertNotNull(renderedRaw);
        assertTrue("InlineMd rendering 50k words must be fast (< 500ms), took " + elapsedInline + "ms", elapsedInline < 500);

        start = System.currentTimeMillis();
        int words = Md.wordCount(largeSingleLine);
        long elapsedWords = System.currentTimeMillis() - start;
        assertEquals(50_000, words);
        assertTrue("Word count on 50k words must be fast (< 500ms), took " + elapsedWords + "ms", elapsedWords < 500);

        start = System.currentTimeMillis();
        BlockDocument doc = BlockDocument.parse(largeSingleLine);
        String backToMd = doc.toMarkdown();
        long elapsedDoc = System.currentTimeMillis() - start;
        assertNotNull(backToMd);
        assertTrue("BlockDocument roundtrip must be fast (< 500ms), took " + elapsedDoc + "ms", elapsedDoc < 500);
    }

    @Test
    public void testLarge50kWordsTexPerformance() {
        StringBuilder sb = new StringBuilder(400_000);
        sb.append("\\documentclass{article}\n\\begin{document}\n");
        for (int i = 0; i < 50_000; i++) {
            if (i % 20 == 0) {
                sb.append("$x_{").append(i).append("} + y$ ");
            } else if (i % 30 == 0) {
                sb.append("\\textbf{term").append(i).append("} ");
            } else if (i % 40 == 0) {
                sb.append("\\ref{sec:").append(i).append("} ");
            } else {
                sb.append("word").append(i).append(" ");
            }
        }
        sb.append("\n\\end{document}");
        String largeTex = sb.toString();

        long start = System.currentTimeMillis();
        TexPreview preview = new TexPreview(new TexPreview.Palette());
        CharSequence rendered = preview.render(largeTex);
        long elapsedPreview = System.currentTimeMillis() - start;
        assertNotNull(rendered);
        assertTrue("TexPreview on 50k words must be fast (< 500ms), took " + elapsedPreview + "ms", elapsedPreview < 500);

        start = System.currentTimeMillis();
        TexPairMatcher.Pair pair = TexPairMatcher.find(largeTex, 500);
        long elapsedPair = System.currentTimeMillis() - start;
        assertTrue("TexPairMatcher on 50k words must be fast (< 50ms), took " + elapsedPair + "ms", elapsedPair < 50);

        start = System.currentTimeMillis();
        TexAutocomplete.EnvironmentExpansion exp = TexAutocomplete.environmentExpansion(largeTex, 500);
        long elapsedExp = System.currentTimeMillis() - start;
        assertTrue("TexAutocomplete expansion on 50k words must be fast (< 50ms), took " + elapsedExp + "ms", elapsedExp < 50);
    }

    @Test
    public void testRealThesisCommandsAndExpansion() {
        assertSuggestion("\\struct", "\\structsection{}");
        assertSuggestion("\\term", "\\term{}");
        assertSuggestion("\\eng", "\\eng{}");
        assertSuggestion("\\bibit", "\\bibitem{}");
        assertSuggestion("\\begin{longt", "\\begin{longtable}{}\n  \\caption{}\\\\ \n  \\hline\n  \n\\end{longtable}");
        assertSuggestion("\\begin{tik", "\\begin{tikzpicture}\n  \n\\end{tikzpicture}");
        assertSuggestion("\\begin{titlep", "\\begin{titlepage}\n\n\\end{titlepage}");
        assertSuggestion("\\begin{minip", "\\begin{minipage}{\\textwidth}\n\n\\end{minipage}");
        assertSuggestion("\\multic", "\\multicolumn{}{c}{}");
        assertSuggestion("\\captions", "\\captionsetup[]{}");
    }
}
