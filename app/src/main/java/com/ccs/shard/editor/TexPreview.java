package com.ccs.shard.editor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal, high-fidelity TeX parser and renderer for Shard.
 *
 * <p>Turns TeX source into publication-grade styled text with native canvas-drawn tables,
 * mathematical equations, vector diagrams, and accurate pagination.
 */
public final class TexPreview {

    /** Structural markers consumed by the PDF exporter. */
    public static final char PAGE_BREAK_MARKER = '\f';
    public static final char LAST_PAGE_MARKER = '\uFFF0';
    public static final char LANDSCAPE_START_MARKER = '\uFFF1';
    public static final char LANDSCAPE_END_MARKER = '\uFFF2';
    public static final char LONGTABLE_HEADER_START_MARKER = '\uFFF3';
    public static final char LONGTABLE_HEADER_END_MARKER = '\uFFF4';
    public static final char LONGTABLE_END_MARKER = '\uFFF5';
    public static final char LONGTABLE_REPEAT_HEADER_MARKER = '\uFFF6';

    public static final class Palette {
        public int text = 0xFF111111;
        public int muted = 0xFF555555;
        public int accent = 0xFF0B57D0;
        public int mathBackground = 0x0A000000;
    }

    private static final int MAX_PREVIEW_LENGTH = 160_000;
    private static final int MAX_SPAN_LOOKAHEAD = 2048;

    private static final String[] DROPPED_ONE_ARGUMENT = {
            "documentclass", "usepackage", "label", "bibliographystyle",
            "bibliography", "pagestyle", "thispagestyle",
            "fancyhf", "fancyhead", "fancyfoot", "hypersetup", "geometry",
            "input", "include", "setlist", "captionsetup", "usetikzlibrary",
            "selectlanguage", "vspace",
    };

    private static final String[] DROPPED_BARE = {
            "maketitle", "clearpage", "newpage", "noindent", "centering",
            "raggedright", "raggedleft", "hline", "toprule", "midrule", "bottomrule",
            "appendix", "onehalfspacing", "doublespacing", "singlespacing",
            "firsthead", "endfirsthead", "head", "endhead", "foot", "endfoot", "lastfoot", "endlastfoot",
            "vfill", "hfill", "bigskip", "medskip", "smallskip", "phantomsection",
            "begingroup", "endgroup", "scriptsize", "footnotesize", "small", "normalsize",
    };

    private static final Pattern NEWCOMMAND_START_PATTERN = Pattern.compile(
            "\\\\(?:re)?newcommand\\*?");

    private static final Pattern BIBITEM_PATTERN = Pattern.compile(
            "\\\\bibitem\\{([^}]+)\\}");
    private static final Pattern DIMENSION_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(cm|mm|pt|em|ex)?");

    private final Palette palette;
    /** Width available to the live TextView in canvas pixels (zero for PDF). */
    private float liveContentWidth;
    private Map<String, String> references = new LinkedHashMap<>();
    private Map<String, Integer> citationNumbers = new LinkedHashMap<>();

    public TexPreview(Palette palette) {
        this.palette = palette;
    }

    /** Set the measured width of the live preview text area (in pixels). */
    public void setLiveContentWidth(float width) {
        liveContentWidth = width > 0f ? width : 0f;
    }

    private static boolean isUkrainianDocument(String preamble, String body) {
        if (preamble != null && (preamble.contains("ukrainian") || preamble.contains("ukr"))) return true;
        if (body != null) {
            int cyrillic = 0;
            for (int i = 0; i < Math.min(body.length(), 1000); i++) {
                char c = body.charAt(i);
                if (c >= '\u0400' && c <= '\u04FF') cyrillic++;
            }
            // Short notes and figure captions may contain only a handful of words.
            if (cyrillic >= 3) return true;
        }
        return false;
    }

    public CharSequence render(String source) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (source == null || source.isEmpty()) return out;
        if (source.length() > MAX_PREVIEW_LENGTH) return source;

        // 1. Extract macros and citations
        int begin = source.indexOf("\\begin{document}");
        String preamble = begin >= 0 ? source.substring(0, begin) : source;
        Map<String, String> varMacros = new LinkedHashMap<>();
        Map<String, ParameterizedMacro> paramMacros = new LinkedHashMap<>();
        extractMacros(preamble, varMacros, paramMacros);

        Map<String, Integer> bibKeys = new LinkedHashMap<>();
        extractBibKeys(source, bibKeys);
        citationNumbers = bibKeys;

        String docTitle = extractCommandArgument(source, "\\title");
        String docAuthor = extractCommandArgument(source, "\\author");
        String docDate = extractCommandArgument(source, "\\date");

        // 2. Extract Document Body
        String body = source;
        if (begin >= 0) {
            int from = begin + "\\begin{document}".length();
            int end = source.indexOf("\\end{document}", from);
            body = end > from ? source.substring(from, end) : source.substring(from);
        }

        boolean isUk = isUkrainianDocument(preamble, body);
        String bibliographyTitle = isUk ? "СПИСОК ЛІТЕРАТУРИ" : "REFERENCES";
        Map<String, String> bodyMacros = new LinkedHashMap<>();
        extractMacros(body, bodyMacros, new LinkedHashMap<String, ParameterizedMacro>());
        if (bodyMacros.containsKey("refname")) {
            bibliographyTitle = bodyMacros.get("refname").trim();
        }
        references = indexReferences(body, preamble);
        boolean sectionsStartNewPage = preamble.contains("\\sectionbreak");

        // 3. Pre-process macros in body
        body = expandMacros(body, varMacros, paramMacros);

        int listDepth = 0;
        boolean ordered = false;
        int orderedIndex = 1;
        int sectionCount = 0;
        int subsectionCount = 0;
        int subsubsectionCount = 0;
        int equationCount = 0;
        int figureCount = 0;
        int bibIndex = 1;

        boolean inTitlePage = false;
        boolean inCenter = false;
        boolean inFlushRight = false;
        boolean inAbstract = false;
        int tableDepth = 0;
        boolean inLongtable = false;
        boolean isLandscape = false;
        int longtableControlBlock = 0;
        String longtableFooter = "";
        boolean longtableHeaderStarted = false;
        boolean longtableHeaderClosed = false;
        int longtableVisibleHeaderStart = -1;
        int longtableContinuationHeaderStart = -1;
        CharSequence longtableRepeatHeader = null;
        SpannableStringBuilder longtableContinuationHeader = new SpannableStringBuilder();
        StringBuilder longtableContinuationRow = new StringBuilder();
        boolean inTikz = false;
        boolean inFigure = false;
        boolean inEquation = false;
        boolean inAlign = false;
        boolean inTheBibliography = false;
        boolean titleRendered = false;

        StringBuilder tikzBuffer = new StringBuilder();
        StringBuilder equationBuffer = new StringBuilder();
        StringBuilder tableRowBuffer = new StringBuilder();
        float[] currentTableColFractions = new float[]{0.25f, 0.25f, 0.25f, 0.25f};

        String[] bodyLines = body.split("\n", -1);
        for (int lineIndex = 0; lineIndex < bodyLines.length; lineIndex++) {
            String rawLine = bodyLines[lineIndex];
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                if (!inTikz && !inEquation && !inAlign && tableDepth == 0) appendParagraphBreak(out);
                continue;
            }

            if (line.equals("\\clearpage") || line.equals("\\newpage")
                    || line.equals("\\pagebreak")) {
                flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
                if (inLongtable && longtableHeaderClosed
                        && hasVisibleContent(longtableRepeatHeader)) {
                    appendInlineStructuralMarker(out, LONGTABLE_END_MARKER);
                    appendPageBreak(out);
                    appendLongtableRepeatHeaderMarker(out, longtableRepeatHeader, true);
                    continue;
                }
                appendPageBreak(out);
                continue;
            }

            if (line.contains("\\maketitle")) {
                if (!docTitle.isEmpty() && !titleRendered) {
                    appendTitleBlock(out, docTitle, docAuthor, docDate);
                    titleRendered = true;
                }
                continue;
            }

            if (line.contains("\\tableofcontents")) {
                appendTableOfContents(out, body, isUk);
                continue;
            }

            String env = environmentOf(line, "\\begin{");
            if (env != null) {
                if (env.equals("titlepage")) {
                    if (hasVisibleContent(out)) appendPageBreak(out);
                    inTitlePage = true;
                } else if (env.equals("itemize") || env.equals("enumerate")) {
                    listDepth++;
                    ordered = env.equals("enumerate");
                    orderedIndex = 1;
                } else if (env.equals("center")) {
                    inCenter = true;
                } else if (env.equals("flushright") || env.equals("minipage")) {
                    inFlushRight = true;
                } else if (env.equals("abstract")) {
                    inAbstract = true;
                    appendAbstractHeader(out, isUk);
                } else if (env.equals("landscape")) {
                    isLandscape = true;
                    flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
                    appendOrientationChange(out, true);
                } else if (env.startsWith("tabular") || env.equals("table")
                        || env.equals("longtable")) {
                    tableDepth++;
                    currentTableColFractions = parseColumnFractions(line);
                    if (env.equals("longtable")) {
                        inLongtable = true;
                        longtableControlBlock = 0;
                        longtableFooter = "";
                        longtableHeaderStarted = false;
                        longtableHeaderClosed = false;
                        longtableVisibleHeaderStart = -1;
                        longtableContinuationHeaderStart = -1;
                        longtableRepeatHeader = null;
                        longtableContinuationHeader.clear();
                        longtableContinuationRow.setLength(0);
                    }
                } else if (env.equals("figure")) {
                    inFigure = true;
                } else if (env.equals("tikzpicture")) {
                    inTikz = true;
                    tikzBuffer.setLength(0);
                } else if (env.equals("equation") || env.equals("equation*")) {
                    inEquation = true;
                    equationBuffer.setLength(0);
                } else if (env.equals("align") || env.equals("align*")) {
                    inAlign = true;
                    equationBuffer.setLength(0);
                } else if (env.equals("theorem") || env.equals("definition") || env.equals("lemma")
                        || env.equals("example") || env.equals("proof") || env.equals("remark")) {
                    appendTheoremHeader(out, env, line, sectionCount, isUk);
                } else if (env.equals("thebibliography")) {
                    inTheBibliography = true;
                    bibIndex = 1;
                    appendStructSection(out, bibliographyTitle);
                }
                if (!inTitlePage && !env.startsWith("equation") && !env.startsWith("align")) {
                    appendParagraphBreak(out);
                }
                continue;
            }

            env = environmentOf(line, "\\end{");
            if (env != null) {
                if (env.equals("titlepage")) {
                    inTitlePage = false;
                    appendPageBreak(out);
                } else if (env.equals("itemize") || env.equals("enumerate")) {
                    listDepth = Math.max(0, listDepth - 1);
                } else if (env.equals("center")) {
                    inCenter = false;
                } else if (env.equals("flushright") || env.equals("minipage")) {
                    inFlushRight = false;
                } else if (env.equals("abstract")) {
                    inAbstract = false;
                } else if (env.equals("landscape")) {
                    isLandscape = false;
                    flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
                    appendOrientationChange(out, false);
                } else if (env.startsWith("tabular") || env.equals("table")
                        || env.equals("longtable")) {
                    flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
                    tableDepth = Math.max(0, tableDepth - 1);
                    if (env.equals("longtable")) {
                        inLongtable = false;
                        longtableControlBlock = 0;
                        if (!longtableFooter.isEmpty()) {
                            appendTableNote(out, longtableFooter);
                            longtableFooter = "";
                        }
                        if (longtableHeaderClosed) {
                            appendInlineStructuralMarker(out, LONGTABLE_END_MARKER);
                        }
                        longtableHeaderStarted = false;
                        longtableHeaderClosed = false;
                        longtableVisibleHeaderStart = -1;
                        longtableContinuationHeaderStart = -1;
                        longtableRepeatHeader = null;
                        longtableContinuationHeader.clear();
                        longtableContinuationRow.setLength(0);
                    }
                } else if (env.equals("figure")) {
                    inFigure = false;
                } else if (env.equals("tikzpicture")) {
                    inTikz = false;
                    if (hasTikzPictureAhead(bodyLines, lineIndex + 1)) {
                        continue;
                    }
                    figureCount++;
                    int captionLine = findCaptionAhead(bodyLines, lineIndex + 1);
                    String caption = captionLine >= 0
                            ? argumentOf(bodyLines[captionLine])
                            : "";
                    appendTikzFigure(out, tikzBuffer.toString(), caption, figureCount, isUk);
                    int figureEnd = findEnvironmentEnd(bodyLines, lineIndex + 1, "figure");
                    if (captionLine >= 0) lineIndex = captionLine;
                    if (figureEnd > lineIndex) {
                        lineIndex = figureEnd;
                        inFigure = false;
                    }
                } else if (env.equals("equation") || env.equals("equation*")
                        || env.equals("align") || env.equals("align*")) {
                    inEquation = false;
                    inAlign = false;
                    equationCount++;
                    boolean numbered = !env.endsWith("*");
                    String eqNum = numbered
                            ? "(" + (sectionCount > 0 ? sectionCount + "." : "") + equationCount + ")"
                            : "";
                    appendEquationBlock(out, equationBuffer.toString().trim(), eqNum);
                    equationBuffer.setLength(0);
                } else if (env.equals("proof")) {
                    appendProofEnd(out);
                } else if (env.equals("thebibliography")) {
                    inTheBibliography = false;
                }
                if (!env.equals("titlepage") && !env.equals("landscape")
                        && !env.startsWith("equation") && !env.startsWith("align")) {
                    appendParagraphBreak(out);
                }
                continue;
            }

            if (inTikz) {
                tikzBuffer.append(line).append("\n");
                continue;
            }

            if (inEquation || inAlign) {
                equationBuffer.append(line).append("\n");
                continue;
            }

            // Structural sections (e.g. \structsection{...})
            if (line.startsWith("\\structsection")) {
                String title = argumentOf(line);
                appendPageBreak(out);
                appendStructSection(out, title);
                continue;
            }

            if (inLongtable) {
                if (line.equals("\\endfirsthead")) {
                    tableRowBuffer.setLength(0);
                    longtableControlBlock = hasLongtableControlAhead(
                            bodyLines, lineIndex + 1, "\\endhead") ? 1 : 0;
                    longtableContinuationHeader.clear();
                    longtableContinuationRow.setLength(0);
                    continue;
                }
                if (line.equals("\\endhead")) {
                    if (longtableControlBlock == 1) {
                        flushTableRow(longtableContinuationHeader, longtableContinuationRow,
                                currentTableColFractions, isLandscape);
                        longtableRepeatHeader = trimmedStyled(longtableContinuationHeader);
                        if (hasVisibleContent(longtableRepeatHeader)) {
                            appendLongtableRepeatHeaderMarker(out, longtableRepeatHeader, false);
                            longtableHeaderClosed = true;
                        }
                    } else {
                        longtableHeaderStarted = true;
                        if (longtableHeaderStarted && !longtableHeaderClosed) {
                            if (longtableVisibleHeaderStart >= 0) {
                                longtableRepeatHeader = trimmedStyled(out.subSequence(
                                        longtableVisibleHeaderStart, out.length()));
                            }
                            appendInlineStructuralMarker(out, LONGTABLE_HEADER_END_MARKER);
                            longtableHeaderClosed = true;
                        }
                    }
                    tableRowBuffer.setLength(0);
                    if (hasLongtableControlAhead(bodyLines, lineIndex + 1, "\\endfoot")) {
                        longtableControlBlock = 2;
                    } else if (hasLongtableControlAhead(
                            bodyLines, lineIndex + 1, "\\endlastfoot")) {
                        longtableControlBlock = 3;
                    } else {
                        longtableControlBlock = 0;
                    }
                    continue;
                }
                if (line.equals("\\endfoot")) {
                    tableRowBuffer.setLength(0);
                    longtableControlBlock = hasLongtableControlAhead(
                            bodyLines, lineIndex + 1, "\\endlastfoot") ? 3 : 0;
                    continue;
                }
                if (line.equals("\\endlastfoot")) {
                    tableRowBuffer.setLength(0);
                    longtableControlBlock = 0;
                    continue;
                }
                if (longtableControlBlock != 0) {
                    if (longtableControlBlock == 1) {
                        if (line.equals("\\hline") || line.equals("\\toprule")
                                || line.equals("\\midrule") || line.equals("\\bottomrule")) {
                            flushTableRow(longtableContinuationHeader,
                                    longtableContinuationRow, currentTableColFractions,
                                    isLandscape);
                        } else if (!isTableControlLine(line)) {
                            if (longtableContinuationRow.length() > 0) {
                                longtableContinuationRow.append(' ');
                            }
                            longtableContinuationRow.append(line);
                            flushCompletedTableRows(longtableContinuationHeader,
                                    longtableContinuationRow, currentTableColFractions,
                                    isLandscape);
                        }
                    }
                    if (longtableControlBlock == 3 && line.contains("\\multicolumn")) {
                        String footer = multicolumnText(line);
                        if (!footer.isEmpty()) longtableFooter = footer;
                    }
                    continue;
                }
            }

            if (line.startsWith("\\caption")) {
                flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
                appendCaption(out, argumentOf(line));
                if (inLongtable && !longtableHeaderStarted
                        && hasEndheadWithoutFirstheadAhead(bodyLines, lineIndex + 1)) {
                    appendInlineStructuralMarker(out, LONGTABLE_HEADER_START_MARKER);
                    longtableHeaderStarted = true;
                    longtableVisibleHeaderStart = out.length();
                }
                continue;
            }

            // Tables / horizontal rules
            if (tableDepth > 0 || line.contains("\\hline") || line.contains("\\toprule")
                    || line.contains("\\midrule") || line.contains("\\bottomrule")) {
                if (inLongtable && !longtableHeaderStarted
                        && hasEndheadWithoutFirstheadAhead(bodyLines, lineIndex + 1)) {
                    appendInlineStructuralMarker(out, LONGTABLE_HEADER_START_MARKER);
                    longtableHeaderStarted = true;
                    longtableVisibleHeaderStart = out.length();
                }
                if (line.equals("\\hline") || line.equals("\\toprule")
                        || line.equals("\\midrule") || line.equals("\\bottomrule")) {
                    flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
                    continue;
                }
                if (line.contains("\\multicolumn")) {
                    flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
                    appendMulticolumn(out, line);
                    continue;
                }
                if (isTableControlLine(line)) continue;
                if (tableDepth > 0) {
                    if (tableRowBuffer.length() > 0) tableRowBuffer.append(' ');
                    tableRowBuffer.append(line);
                    flushCompletedTableRows(out, tableRowBuffer, currentTableColFractions, isLandscape);
                    continue;
                }
            }

            // Bibliography items
            if (line.startsWith("\\bibitem")) {
                String key = argumentOf(line);
                int num = bibKeys.containsKey(key) ? bibKeys.get(key) : bibIndex++;
                String content = line.substring(line.indexOf('}') + 1).trim();
                appendBibItem(out, num, content);
                continue;
            }

            int heading = headingLevel(line);
            if (heading > 0) {
                if (heading == 1 && sectionsStartNewPage) appendPageBreak(out);
                if (heading == 1) {
                    sectionCount++;
                    subsectionCount = 0;
                    subsubsectionCount = 0;
                    equationCount = 0;
                    appendSectionHeading(out, sectionCount, argumentOf(line));
                } else if (heading == 2) {
                    subsectionCount++;
                    subsubsectionCount = 0;
                    String prefix = (sectionCount > 0 ? sectionCount + "." : "") + subsectionCount + "  ";
                    appendSubsectionHeading(out, prefix + argumentOf(line));
                } else if (heading == 3) {
                    subsubsectionCount++;
                    String prefix = (sectionCount > 0 ? sectionCount + "." : "")
                            + (subsectionCount > 0 ? subsectionCount + "." : "")
                            + subsubsectionCount + "  ";
                    appendSubsubsectionHeading(out, prefix + argumentOf(line));
                }
                continue;
            }

            if (line.startsWith("\\item")) {
                String itemText = line.substring(5).trim();
                int[] opt = bracketed(itemText, 0);
                String bullet = "• ";
                if (opt != null) {
                    bullet = itemText.substring(opt[0], opt[1]) + " ";
                    itemText = itemText.substring(opt[2]).trim();
                } else if (ordered) {
                    bullet = (orderedIndex++) + ". ";
                }
                appendListItem(out, bullet, itemText, listDepth);
                continue;
            }

            if (inTitlePage) {
                appendTitlePageLine(out, line, inCenter, inFlushRight);
            } else if (inFlushRight) {
                appendFlushRightLine(out, line);
            } else if (inAbstract) {
                appendAbstractBody(out, line);
            } else {
                appendBody(out, line);
            }
        }
        flushTableRow(out, tableRowBuffer, currentTableColFractions, isLandscape);
        return out;
    }

    // ---------------------------------------------------------------- pieces

    private void appendTitleBlock(SpannableStringBuilder out, String title, String author, String date) {
        appendParagraphBreak(out);
        int start = out.length();
        out.append(inline(title));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(1.4f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n");

        if (author != null && !author.isEmpty()) {
            start = out.length();
            String cleanAuthor = author.replace("\\and", "   •   ");
            out.append(inline(cleanAuthor));
            end = out.length();
            out.setSpan(new RelativeSizeSpan(1.0f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(palette.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append("\n");
        }

        if (date != null && !date.isEmpty()) {
            start = out.length();
            String cleanDate = date.replace("\\today", "2026");
            out.append(inline(cleanDate));
            end = out.length();
            out.setSpan(new RelativeSizeSpan(0.9f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(palette.muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append("\n");
        }
        out.append("\n");
    }

    private void appendTitlePageLine(SpannableStringBuilder out, String line, boolean inCenter, boolean inFlushRight) {
        String clean = line.trim();
        if (clean.isEmpty() || clean.startsWith("\\thispagestyle")) return;
        if (clean.startsWith("\\vspace")) {
            appendVerticalSpace(out, dimensionToEm(clean, 2.0f));
            return;
        }
        if (clean.equals("\\vfill")) {
            appendVerticalSpace(out, 2.5f);
            return;
        }

        ensureLineBreak(out);
        int start = out.length();
        out.append(inline(clean));
        int end = out.length();

        out.setSpan(new RelativeSizeSpan(0.95f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (inFlushRight) {
            out.setSpan(new LeadingMarginSpan.Standard(190),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (inCenter) {
            out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ensureLineBreak(out);
    }

    private static float dimensionToEm(String text, float fallback) {
        Matcher matcher = DIMENSION_PATTERN.matcher(text);
        if (!matcher.find()) return fallback;
        try {
            float val = Float.parseFloat(matcher.group(1));
            String unit = matcher.group(2);
            if (unit == null || unit.equals("em")) return val;
            if (unit.equals("ex")) return val * 0.5f;
            if (unit.equals("pt")) return val / 12f;
            if (unit.equals("mm")) return (val / 25.4f) * (72f / 12f);
            if (unit.equals("cm")) return (val / 2.54f) * (72f / 12f) * 0.5f;
            return val;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void appendStructSection(SpannableStringBuilder out, String title) {
        appendParagraphBreak(out);
        int start = out.length();
        out.append(title.toUpperCase(Locale.ROOT));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(1.05f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    private void appendSectionHeading(SpannableStringBuilder out, int sectionNumber, String title) {
        appendParagraphBreak(out);
        int start = out.length();
        if (sectionNumber > 0) {
            out.append(String.valueOf(sectionNumber)).append("  ");
        }
        out.append(inline(title));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(1.2f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    private void appendSubsectionHeading(SpannableStringBuilder out, String title) {
        appendParagraphBreak(out);
        int start = out.length();
        out.append(inline(title));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(1.05f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    private void appendSubsubsectionHeading(SpannableStringBuilder out, String title) {
        appendParagraphBreak(out);
        int start = out.length();
        out.append(inline(title));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(1.0f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    private void appendTheoremHeader(SpannableStringBuilder out, String env, String line,
                                     int sectionCount, boolean isUk) {
        appendParagraphBreak(out);
        String name = "";
        int optOpen = line.indexOf('[');
        int optClose = optOpen >= 0 ? line.indexOf(']', optOpen) : -1;
        if (optOpen >= 0 && optClose > optOpen) {
            name = line.substring(optOpen + 1, optClose).trim();
        }

        String label;
        switch (env) {
            case "theorem": label = isUk ? "Теорема" : "Theorem"; break;
            case "definition": label = isUk ? "Означення" : "Definition"; break;
            case "lemma": label = isUk ? "Лема" : "Lemma"; break;
            case "example": label = isUk ? "Приклад" : "Example"; break;
            case "remark": label = isUk ? "Зауваження" : "Remark"; break;
            case "proof": label = isUk ? "Доведення." : "Proof."; break;
            default: label = capitalize(env); break;
        }

        int start = out.length();
        out.append(label);
        if (!env.equals("proof")) {
            if (!name.isEmpty()) {
                out.append(" (").append(name).append(")");
            }
            out.append(". ");
        } else {
            out.append(" ");
        }
        int end = out.length();
        out.setSpan(new StyleSpan(env.equals("proof") ? Typeface.ITALIC : Typeface.BOLD),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.text), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void appendProofEnd(SpannableStringBuilder out) {
        int start = out.length();
        out.append(" \u25A0\n\n");
        int end = out.length();
        out.setSpan(new ForegroundColorSpan(palette.muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private void appendTableOfContents(SpannableStringBuilder out, String body, boolean isUk) {
        appendParagraphBreak(out);
        int start = out.length();
        String tocTitle = isUk ? "ЗМІСТ" : "TABLE OF CONTENTS";
        out.append(tocTitle).append("\n\n");
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(1.1f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int sec = 0;
        int subsec = 0;
        int subsubsec = 0;
        int pageGuess = 1;

        String[] lines = body.split("\n", -1);
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.equals("\\newpage") || line.equals("\\clearpage") || line.equals("\\pagebreak")) {
                pageGuess++;
            }
            if (line.startsWith("\\section")) {
                sec++;
                subsec = 0;
                subsubsec = 0;
                String title = argumentOf(line);
                if (!title.isEmpty()) {
                    appendTocItem(out, cleanInlineText(title), sec + "  ", String.valueOf(pageGuess), true, 1);
                }
            } else if (line.startsWith("\\subsection")) {
                subsec++;
                subsubsec = 0;
                String title = argumentOf(line);
                if (!title.isEmpty()) {
                    String prefix = (sec > 0 ? sec + "." : "") + subsec + "  ";
                    appendTocItem(out, cleanInlineText(title), prefix, String.valueOf(pageGuess), false, 2);
                }
            } else if (line.startsWith("\\subsubsection")) {
                subsubsec++;
                String title = argumentOf(line);
                if (!title.isEmpty()) {
                    String prefix = (sec > 0 ? sec + "." : "") + (subsec > 0 ? subsec + "." : "") + subsubsec + "  ";
                    appendTocItem(out, cleanInlineText(title), prefix, String.valueOf(pageGuess), false, 3);
                }
            }
        }
        appendParagraphBreak(out);
    }

    private void appendTocItem(SpannableStringBuilder out, String title, String prefix, String pageNum,
                               boolean isMajor, int level) {
        ensureLineBreak(out);
        int start = out.length();
        out.append(prefix).append(title).append("   ").append(pageNum);
        int end = out.length();
        out.setSpan(new TocRowSpan(prefix, title, pageNum, isMajor, level, 499f,
                        liveContentWidth),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private void appendEquationBlock(SpannableStringBuilder out, String rawMath, String eqNum) {
        appendParagraphBreak(out);
        String cleaned = stripMathLabels(rawMath);
        int start = out.length();
        out.append(cleaned).append(" ").append(eqNum);
        int end = out.length();
        out.setSpan(new EquationSpan(cleaned, eqNum, 499f, liveContentWidth),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private static String stripMathLabels(String math) {
        return math.replaceAll("\\\\label\\{[^}]+\\}", "").trim();
    }

    private void appendTikzFigure(SpannableStringBuilder out, String tikzSource, String caption,
                                  int figureNumber, boolean isUk) {
        appendParagraphBreak(out);
        String figPrefix = isUk ? "Рисунок " : "Figure ";
        String fullCaption = figPrefix + figureNumber + (caption.isEmpty() ? "" : " – " + caption);
        int start = out.length();
        out.append(fullCaption);
        int end = out.length();
        out.setSpan(new TikzFigureSpan(499f, 140f, tikzSource, fullCaption, liveContentWidth),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private void appendBibItem(SpannableStringBuilder out, int num, String content) {
        ensureLineBreak(out);
        int start = out.length();
        out.append("[").append(String.valueOf(num)).append("] ");
        out.append(inline(content));
        int end = out.length();
        out.setSpan(new LeadingMarginSpan.Standard(20), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n");
    }

    private void appendAbstractHeader(SpannableStringBuilder out, boolean isUk) {
        appendParagraphBreak(out);
        int start = out.length();
        out.append(isUk ? "АНОТАЦІЯ" : "ABSTRACT");
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(1.1f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    private void appendAbstractBody(SpannableStringBuilder out, String line) {
        int start = out.length();
        out.append(inline(line));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(0.95f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new LeadingMarginSpan.Standard(24), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n");
    }

    private void appendCaption(SpannableStringBuilder out, String caption) {
        appendParagraphBreak(out);
        int start = out.length();
        out.append(inline(caption));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    private void appendTableRow(SpannableStringBuilder out, String line, float[] colFractions, boolean isLandscape) {
        String clean = line.replace("\\hline", "").trim();
        List<String> cols = splitTopLevel(clean, '&');
        if (cols.isEmpty()) return;

        List<String> cleanedCols = new ArrayList<>();
        StringBuilder rowText = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            // Parse references and inline commands before removing braces. Otherwise a cell
            // such as \pageref{LastPage} turns into an unresolvable literal.
            String inlineCell = inline(col.trim()).toString();
            cleanedCols.add(inlineCell.replace(
                    String.valueOf(LAST_PAGE_MARKER), "2"));
            if (i > 0) rowText.append("   │   ");
            rowText.append(inlineCell);
        }

        ensureLineBreak(out);
        int start = out.length();
        out.append(rowText.toString().trim());
        int end = out.length();
        out.setSpan(new TableCellSpan(cleanedCols, colFractions, isLandscape, false, false,
                        11.5f, 0xFF111111, 0xFFCCCCCC, 0, liveContentWidth),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private static String cleanCellText(String cell) {
        return cell
                .replace("\\textbf{", "")
                .replace("\\textit{", "")
                .replace("\\texttt{", "")
                .replace("\\text{", "")
                .replace("\\checkmark", "✓")
                .replace("\\$", "$")
                .replace("\\&", "&")
                .replace("\\_", "_")
                .replace("\\%", "%")
                .replace("}", "")
                .replace("{", "")
                .replace("``", "“")
                .replace("''", "”")
                .trim();
    }

    private void appendMulticolumn(SpannableStringBuilder out, String line) {
        String text = multicolumnText(line);
        if (text.isEmpty()) return;
        ensureLineBreak(out);
        int start = out.length();
        out.append(inline(text));
        int end = out.length();
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private static String multicolumnText(String line) {
        int pos = line.indexOf("\\multicolumn");
        if (pos < 0) return "";
        int[] arg1 = braced(line, pos + "\\multicolumn".length());
        int[] arg2 = arg1 == null ? null : braced(line, arg1[2]);
        int[] arg3 = arg2 == null ? null : braced(line, arg2[2]);
        return arg3 == null ? "" : line.substring(arg3[0], arg3[1]).trim();
    }

    private void appendTableNote(SpannableStringBuilder out, String note) {
        appendParagraphBreak(out);
        int start = out.length();
        out.append(inline(note));
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(palette.muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    private void appendListItem(SpannableStringBuilder out, String bullet, String text, int depth) {
        ensureLineBreak(out);
        int start = out.length();
        out.append(bullet).append(inline(text));
        int end = out.length();
        int margin = Math.max(16, depth * 20);
        out.setSpan(new LeadingMarginSpan.Standard(margin, margin + 12), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private void appendFlushRightLine(SpannableStringBuilder out, String line) {
        ensureLineBreak(out);
        int start = out.length();
        out.append(inline(line));
        int end = out.length();
        out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private void appendBody(SpannableStringBuilder out, String line) {
        ensureLineBreak(out);
        out.append(inline(line));
        out.append('\n');
    }

    private void flushCompletedTableRows(SpannableStringBuilder out, StringBuilder buffer,
                                         float[] colFractions, boolean isLandscape) {
        String str = buffer.toString();
        int lastTerm = -1;
        int searchFrom = 0;
        while (true) {
            int pos = findTableRowTerminator(str.substring(searchFrom));
            if (pos < 0) break;
            int actualPos = searchFrom + pos;
            String row = str.substring(searchFrom, actualPos);
            appendTableRow(out, row, colFractions, isLandscape);
            searchFrom = actualPos + 2;
            lastTerm = searchFrom;
        }
        if (lastTerm >= 0) {
            buffer.delete(0, lastTerm);
        }
    }

    private void flushTableRow(SpannableStringBuilder out, StringBuilder buffer,
                               float[] colFractions, boolean isLandscape) {
        if (buffer.length() == 0) return;
        String remaining = buffer.toString().trim();
        if (!remaining.isEmpty()) {
            appendTableRow(out, remaining, colFractions, isLandscape);
        }
        buffer.setLength(0);
    }

    private static float[] parseColumnFractions(String line) {
        int open = line.indexOf('{');
        int close = open >= 0 ? line.indexOf('}', open) : -1;
        if (open < 0 || close < 0) {
            return new float[]{0.25f, 0.25f, 0.25f, 0.25f};
        }
        String spec = line.substring(open + 1, close);
        List<Float> weights = new ArrayList<>();
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == 'l' || c == 'c' || c == 'r' || c == 'X') {
                weights.add(1.0f);
            } else if (c == 'p' || c == 'm' || c == 'b') {
                weights.add(1.5f);
                int brace = spec.indexOf('}', i);
                if (brace > i) i = brace;
            }
        }
        if (weights.isEmpty()) {
            return new float[]{0.25f, 0.25f, 0.25f, 0.25f};
        }
        float total = 0f;
        for (float w : weights) total += w;
        float[] result = new float[weights.size()];
        for (int i = 0; i < weights.size(); i++) {
            result[i] = weights.get(i) / total;
        }
        return result;
    }

    private static int findTableRowTerminator(CharSequence text) {
        int braceDepth = 0;
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                char next = text.charAt(i + 1);
                if (next == '\\' && braceDepth == 0) return i;
                i++;
                continue;
            }
            if (c == '{') braceDepth++;
            else if (c == '}' && braceDepth > 0) braceDepth--;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int braceDepth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '{') braceDepth++;
            else if (c == '}' && braceDepth > 0) braceDepth--;
            else if (c == separator && braceDepth == 0) {
                parts.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < text.length()) {
            parts.add(text.substring(start).trim());
        }
        return parts;
    }

    private static boolean isTableControlLine(String line) {
        return line.equals("\\endfirsthead") || line.equals("\\endhead")
                || line.equals("\\endfoot") || line.equals("\\endlastfoot");
    }

    private static boolean hasLongtableControlAhead(String[] lines, int from, String control) {
        for (int i = from; i < lines.length; i++) {
            String l = stripComment(lines[i]).trim();
            if (l.equals(control)) return true;
            if (l.equals("\\end{longtable}") || l.equals("\\end{tabular}")) return false;
        }
        return false;
    }

    private static boolean hasEndheadWithoutFirstheadAhead(String[] lines, int from) {
        return hasLongtableControlAhead(lines, from, "\\endhead")
                && !hasLongtableControlAhead(lines, from, "\\endfirsthead");
    }

    private static boolean hasTikzPictureAhead(String[] lines, int from) {
        for (int i = Math.max(0, from); i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.equals("\\end{figure}")) return false;
            if (line.startsWith("\\begin{tikzpicture}")) return true;
        }
        return false;
    }

    private static int findCaptionAhead(String[] lines, int from) {
        for (int i = Math.max(0, from); i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.equals("\\end{figure}")) return -1;
            if (line.startsWith("\\caption")) return i;
        }
        return -1;
    }

    private static int findEnvironmentEnd(String[] lines, int from, String environment) {
        String target = "\\end{" + environment + "}";
        for (int i = Math.max(0, from); i < lines.length; i++) {
            if (stripComment(lines[i]).trim().equals(target)) return i;
        }
        return -1;
    }

    private static String extractCommandArgument(String text, String command) {
        if (text == null) return "";
        int pos = findCommand(text, command, 0);
        if (pos < 0) return "";
        int[] argument = bracedUnlimited(text, pos + command.length());
        return argument == null ? ""
                : text.substring(argument[0], argument[1]).trim();
    }

    private void appendParagraphBreak(SpannableStringBuilder out) {
        if (out.length() == 0) return;
        if (out.length() >= 2 && out.charAt(out.length() - 1) == '\n'
                && out.charAt(out.length() - 2) == '\n') {
            return;
        }
        out.append(out.charAt(out.length() - 1) == '\n' ? "\n" : "\n\n");
    }

    private void ensureLineBreak(SpannableStringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    private void appendVerticalSpace(SpannableStringBuilder out, float em) {
        if (em <= 0f) return;
        ensureLineBreak(out);
        int start = out.length();
        out.append('\uFFFC');
        out.setSpan(new VerticalSpaceSpan(em), start, start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private void appendPageBreak(SpannableStringBuilder out) {
        if (!hasVisibleContent(out)) return;
        int last = out.length() - 1;
        while (last >= 0 && (Character.isWhitespace(out.charAt(last))
                || isStructuralMarker(out.charAt(last)))) {
            if (out.charAt(last) == PAGE_BREAK_MARKER) return;
            last--;
        }
        ensureLineBreak(out);
        appendStructuralMarker(out, PAGE_BREAK_MARKER);
    }

    private void appendOrientationChange(SpannableStringBuilder out, boolean landscape) {
        appendPageBreak(out);
        appendStructuralMarker(out,
                landscape ? LANDSCAPE_START_MARKER : LANDSCAPE_END_MARKER);
    }

    private void appendStructuralMarker(SpannableStringBuilder out, char markerValue) {
        int marker = out.length();
        out.append(markerValue);
        out.setSpan(new MarkerSpan("", palette.muted), marker, marker + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private void appendInlineStructuralMarker(SpannableStringBuilder out, char markerValue) {
        int marker = out.length();
        out.append(markerValue);
        out.setSpan(new MarkerSpan("", palette.muted), marker, marker + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void appendLongtableRepeatHeaderMarker(SpannableStringBuilder out,
                                                    CharSequence header,
                                                    boolean repeatOnFirstPage) {
        int marker = out.length();
        out.append(LONGTABLE_REPEAT_HEADER_MARKER);
        out.setSpan(new LongtableRepeatHeaderSpan(header, repeatOnFirstPage),
                marker, marker + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static boolean isStructuralMarker(char c) {
        return c == PAGE_BREAK_MARKER || c == LANDSCAPE_START_MARKER
                || c == LANDSCAPE_END_MARKER || c == LONGTABLE_HEADER_START_MARKER
                || c == LONGTABLE_HEADER_END_MARKER || c == LONGTABLE_END_MARKER
                || c == LONGTABLE_REPEAT_HEADER_MARKER;
    }

    private static boolean hasVisibleContent(CharSequence text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !isStructuralMarker(c)) return true;
        }
        return false;
    }

    private static CharSequence trimmedStyled(CharSequence text) {
        if (text == null) return "";
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int end = text.length();
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return text.subSequence(start, end);
    }

    // ---------------------------------------------------------------- formatting

    public CharSequence inline(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) return "";
        if (rawLine.length() > MAX_PREVIEW_LENGTH) return rawLine;

        String line = rawLine
                .replace("---", "—")
                .replace("--", "–")
                .replace("``", "“")
                .replace("''", "”")
                .replace("~", "\u00A0")
                .replace("\\&", "&")
                .replace("\\%", "%")
                .replace("\\$", "$")
                .replace("\\_", "_")
                .replace("\\#", "#")
                .replace("\\LaTeX", "LaTeX")
                .replace("\\TeX", "TeX")
                .replace("\\dots", "…")
                .replace("\\ldots", "…")
                .replace("\\checkmark", "✓");

        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        int length = line.length();
        int plainStart = 0;

        while (i < length) {
            char c = line.charAt(i);

            if (c == '$') {
                int run = (i + 1 < length && line.charAt(i + 1) == '$') ? 2 : 1;
                int limit = Math.min(length, i + run + MAX_SPAN_LOOKAHEAD);
                int close = line.indexOf(run == 2 ? "$$" : "$", i + run);
                if (close >= 0 && close < limit) {
                    if (i > plainStart) out.append(line, plainStart, i);
                    String math = line.substring(i + run, close);
                    String formattedMath = formatMath(math);
                    int start = out.length();
                    out.append(formattedMath);
                    int end = out.length();
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + run;
                    plainStart = i;
                    continue;
                }
            }

            if (c == '\\' && i + 1 < length) {
                char next = line.charAt(i + 1);
                if (next == '\\') {
                    if (i > plainStart) out.append(line, plainStart, i);
                    out.append('\n');
                    i += 2;
                    int[] spacing = bracketed(line, i);
                    if (spacing != null) {
                        appendVerticalSpace(out, dimensionToEm(
                                line.substring(spacing[0], spacing[1]), 0f));
                        i = spacing[2];
                    }
                    plainStart = i;
                    continue;
                }
                if (!Character.isLetter(next)) {
                    if (i > plainStart) out.append(line, plainStart, i);
                    out.append(next);
                    i += 2;
                    plainStart = i;
                    continue;
                }
                int wordEnd = i + 1;
                while (wordEnd < length && Character.isLetter(line.charAt(wordEnd))) wordEnd++;
                String command = line.substring(i + 1, wordEnd);
                if (i > plainStart) out.append(line, plainStart, i);
                i = applyCommand(out, line, command, wordEnd);
                plainStart = i;
                continue;
            }

            if (c == '{' || c == '}') {
                if (i > plainStart) out.append(line, plainStart, i);
                i++;
                plainStart = i;
                continue;
            }
            i++;
        }

        if (plainStart < length) {
            out.append(line, plainStart, length);
        }
        return out;
    }

    private static String cleanInlineText(String text) {
        if (text == null) return "";
        return text
                .replace("\\textbf{", "")
                .replace("\\textit{", "")
                .replace("\\texttt{", "")
                .replace("\\&", "&")
                .replace("\\%", "%")
                .replace("\\$", "$")
                .replace("\\_", "_")
                .replace("\\LaTeX", "LaTeX")
                .replace("\\TeX", "TeX")
                .replace("}", "")
                .replace("{", "")
                .trim();
    }

    public static String formatMath(String math) {
        if (math == null || math.isEmpty()) return "";
        String s = math.trim();

        // Strip labels & comments
        s = s.replaceAll("\\\\label\\{[^}]+\\}", "");
        s = s.replaceAll("\\\\tag\\{[^}]+\\}", "");

        // Greek letters (lowercase)
        s = s.replace("\\alpha", "α")
                .replace("\\beta", "β")
                .replace("\\gamma", "γ")
                .replace("\\delta", "δ")
                .replace("\\epsilon", "ε")
                .replace("\\varepsilon", "ε")
                .replace("\\zeta", "ζ")
                .replace("\\eta", "η")
                .replace("\\theta", "θ")
                .replace("\\iota", "ι")
                .replace("\\kappa", "κ")
                .replace("\\lambda", "λ")
                .replace("\\mu", "μ")
                .replace("\\nu", "ν")
                .replace("\\xi", "ξ")
                .replace("\\pi", "π")
                .replace("\\rho", "ρ")
                .replace("\\sigma", "σ")
                .replace("\\tau", "τ")
                .replace("\\phi", "φ")
                .replace("\\chi", "χ")
                .replace("\\psi", "ψ")
                .replace("\\omega", "ω");

        // Greek letters (uppercase)
        s = s.replace("\\Gamma", "Γ")
                .replace("\\Delta", "Δ")
                .replace("\\Theta", "Θ")
                .replace("\\Lambda", "Λ")
                .replace("\\Xi", "Ξ")
                .replace("\\Pi", "Π")
                .replace("\\Sigma", "Σ")
                .replace("\\Phi", "Φ")
                .replace("\\Psi", "Ψ")
                .replace("\\Omega", "Ω");

        // Math operators & symbols
        s = s.replace("\\to", "→")
                .replace("\\leftarrow", "←")
                .replace("\\rightarrow", "→")
                .replace("\\Leftarrow", "⇐")
                .replace("\\Rightarrow", "⇒")
                .replace("\\iff", "⇔")
                .replace("\\implies", "⇒")
                .replace("\\leq", "≤")
                .replace("\\le", "≤")
                .replace("\\geq", "≥")
                .replace("\\ge", "≥")
                .replace("\\neq", "≠")
                .replace("\\approx", "≈")
                .replace("\\equiv", "≡")
                .replace("\\sim", "∼")
                .replace("\\infty", "∞")
                .replace("\\pm", "±")
                .replace("\\times", "×")
                .replace("\\cdot", "·")
                .replace("\\div", "÷")
                .replace("\\circ", "∘")
                .replace("\\int", "∫")
                .replace("\\iint", "∬")
                .replace("\\iiint", "∭")
                .replace("\\oint", "∮")
                .replace("\\sum", "∑")
                .replace("\\prod", "∏")
                .replace("\\partial", "∂")
                .replace("\\nabla", "∇")
                .replace("\\in", "∈")
                .replace("\\notin", "∉")
                .replace("\\subset", "⊂")
                .replace("\\subseteq", "⊆")
                .replace("\\cap", "∩")
                .replace("\\cup", "∪")
                .replace("\\forall", "∀")
                .replace("\\exists", "∃")
                .replace("\\emptyset", "∅")
                .replace("\\quad", "   ")
                .replace("\\qquad", "      ")
                .replace("\\,", " ")
                .replace("\\;", " ")
                .replace("\\!", "")
                .replace("\\left(", "(")
                .replace("\\right)", ")")
                .replace("\\left[", "[")
                .replace("\\right]", "]")
                .replace("\\left\\{", "{")
                .replace("\\right\\}", "}")
                .replace("\\dots", "…")
                .replace("\\cdots", "⋯")
                .replace("\\vdots", "⋮")
                .replace("\\ddots", "⋱");

        // Blackboard & Cal letters
        s = s.replace("\\mathbb{R}", "ℝ")
                .replace("\\mathbb{N}", "ℕ")
                .replace("\\mathbb{Z}", "ℤ")
                .replace("\\mathbb{C}", "ℂ")
                .replace("\\mathbb{Q}", "ℚ")
                .replace("\\mathcal{S}", "𝒮")
                .replace("\\mathcal{V}", "𝒱")
                .replace("\\mathcal{E}", "ℰ")
                .replace("\\mathcal{M}", "ℳ")
                .replace("\\mathcal{O}", "𝒪");

        // Math functions (strip backslash)
        s = s.replaceAll("\\\\(sin|cos|tan|cot|sec|csc|sinh|cosh|tanh|ln|log|exp|lim|det|max|min|sup|inf|gcd|deg|dim|ker|hom|arg)", "$1");
        s = s.replaceAll("\\\\mathbf\\{([^}]+)\\}", "$1");
        s = s.replaceAll("\\\\text(?:rm)?\\{([^}]+)\\}", "$1");
        s = s.replaceAll("\\\\math(?:cal|bb|bf|it|rm|sf|tt)\\{([^}]+)\\}", "$1");

        // Fractions: \frac{a}{b} -> a/b
        s = replaceFractions(s);

        // Square roots: \sqrt{a} -> √(a)
        s = s.replaceAll("\\\\sqrt\\{([^}]+)\\}", "√($1)");
        s = s.replaceAll("\\\\sqrt\\[([^]]+)\\]\\{([^}]+)\\}", "$1√($2)");

        // Superscripts
        s = s.replace("^{i\\pi}", "ⁱᵖⁱ")
                .replace("^2", "²")
                .replace("^3", "³")
                .replace("^4", "⁴")
                .replace("^0", "⁰")
                .replace("^1", "¹")
                .replace("^n", "ⁿ")
                .replace("^i", "ⁱ")
                .replace("^x", "ˣ")
                .replace("^t", "ᵗ")
                .replace("^+", "⁺")
                .replace("^-", "⁻");

        // Subscripts
        s = s.replace("_0", "₀")
                .replace("_1", "₁")
                .replace("_2", "₂")
                .replace("_3", "₃")
                .replace("_i", "ᵢ")
                .replace("_j", "ⱼ")
                .replace("_k", "ₖ")
                .replace("_n", "ₙ")
                .replace("_m", "ₘ")
                .replace("_t", "ₜ")
                .replace("_x", "ₓ")
                .replace("_y", "ᵧ");

        // Matrix environment cleanup
        s = s.replace("\\begin{pmatrix}", "(")
                .replace("\\end{pmatrix}", ")")
                .replace("\\begin{bmatrix}", "[")
                .replace("\\end{bmatrix}", "]")
                .replace("\\begin{matrix}", "")
                .replace("\\end{matrix}", "")
                .replace("\\begin{array}", "")
                .replace("\\end{array}", "");

        // Remove double braces
        s = s.replace("{", "").replace("}", "");
        return s.trim();
    }

    private static String replaceFractions(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = text.length();
        while (i < len) {
            int pos = text.indexOf("\\frac", i);
            if (pos < 0) {
                pos = text.indexOf("\\dfrac", i);
            }
            if (pos < 0) {
                sb.append(text.substring(i));
                break;
            }
            sb.append(text, i, pos);
            int cmdLen = text.startsWith("\\dfrac", pos) ? 6 : 5;
            int[] num = braced(text, pos + cmdLen);
            int[] den = num == null ? null : braced(text, num[2]);
            if (num != null && den != null) {
                String n = formatMath(text.substring(num[0], num[1]));
                String d = formatMath(text.substring(den[0], den[1]));
                if (n.length() <= 3 && d.length() <= 3 && !n.contains(" ") && !d.contains(" ")) {
                    sb.append(n).append("/").append(d);
                } else {
                    sb.append("(").append(n).append(") / (").append(d).append(")");
                }
                i = den[2];
            } else {
                sb.append(text.substring(pos, pos + cmdLen));
                i = pos + cmdLen;
            }
        }
        return sb.toString();
    }

    private int applyCommand(SpannableStringBuilder out, String line,
                             String command, int after) {
        int droppedArguments = droppedArgumentCount(command);
        if (droppedArguments >= 0) return skipCommandArguments(line, after, droppedArguments);
        for (String dropped : DROPPED_BARE) {
            if (dropped.equals(command)) return after;
        }

        if (command.equals("MakeUppercase")) {
            int[] argument = braced(line, after);
            if (argument != null) {
                out.append(inline(line.substring(argument[0], argument[1])
                        .toUpperCase(Locale.ROOT)));
                return argument[2];
            }
            return after;
        }

        if (command.equals("MakeLowercase")) {
            int[] argument = braced(line, after);
            if (argument != null) {
                out.append(inline(line.substring(argument[0], argument[1])
                        .toLowerCase(Locale.ROOT)));
                return argument[2];
            }
            return after;
        }

        if (command.equals("href")) {
            int[] url = braced(line, after);
            int[] label = url == null ? null : braced(line, url[2]);
            if (label != null) {
                styled(out, line.substring(label[0], label[1]), new UnderlineSpan());
                return label[2];
            }
            return after;
        }
        if (command.equals("url")) {
            int[] url = braced(line, after);
            if (url != null) {
                styled(out, line.substring(url[0], url[1]), new UnderlineSpan());
                return url[2];
            }
            return after;
        }

        if (command.equals("eqref")) {
            int[] argument = braced(line, after);
            if (argument == null) return after;
            String key = line.substring(argument[0], argument[1]).trim();
            String resolved = references.get(key);
            out.append("(").append(resolved == null || resolved.isEmpty() ? "1" : resolved).append(")");
            return argument[2];
        }

        if (command.equals("ref") || command.equals("pageref")) {
            int[] argument = braced(line, after);
            if (argument == null) return after;
            String key = line.substring(argument[0], argument[1]).trim();
            if (command.equals("pageref") && key.equals("LastPage")) {
                int marker = out.length();
                out.append(LAST_PAGE_MARKER);
                out.setSpan(new MarkerSpan("2", palette.muted), marker, marker + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                String resolved = references.get(key);
                out.append(resolved == null || resolved.isEmpty() ? "1" : resolved);
            }
            return argument[2];
        }

        if (command.equals("cite")) {
            int[] argument = braced(line, after);
            if (argument == null) return after;
            String[] keys = line.substring(argument[0], argument[1]).split(",");
            StringBuilder citation = new StringBuilder("[");
            for (String rawKey : keys) {
                if (citation.length() > 1) citation.append(", ");
                Integer number = citationNumbers.get(rawKey.trim());
                citation.append(number == null ? "1" : number);
            }
            citation.append(']');
            out.append(citation);
            return argument[2];
        }

        Object[] spans = spansFor(command);
        if (spans != null) {
            int[] argument = braced(line, after);
            if (argument != null) {
                styled(out, line.substring(argument[0], argument[1]), spans);
                return argument[2];
            }
            return after;
        }

        int[] argument = braced(line, after);
        if (argument != null) {
            out.append(inline(line.substring(argument[0], argument[1])));
            return argument[2];
        }
        return after;
    }

    private static int droppedArgumentCount(String command) {
        switch (command) {
            case "setlength":
            case "setcounter":
            case "numberwithin":
            case "newcommand":
            case "renewcommand":
            case "fancypagestyle":
                return 2;
            case "addcontentsline":
                return 3;
            case "titlespacing":
                return 4;
            case "titleformat":
                return 5;
            default:
                for (String dropped : DROPPED_ONE_ARGUMENT) {
                    if (dropped.equals(command)) return 1;
                }
                return -1;
        }
    }

    private static int skipCommandArguments(String text, int from, int count) {
        int cursor = from;
        if (cursor < text.length() && text.charAt(cursor) == '*') cursor++;
        for (int i = 0; i < count; i++) {
            cursor = skipWhitespace(text, cursor);
            int[] optional = bracketed(text, cursor);
            while (optional != null) {
                cursor = skipWhitespace(text, optional[2]);
                optional = bracketed(text, cursor);
            }
            int[] argument = braced(text, cursor);
            if (argument == null) return cursor;
            cursor = argument[2];
        }
        return cursor;
    }

    private Object[] spansFor(String command) {
        switch (command) {
            case "textbf":
            case "bf":
            case "term":
                return new Object[]{new StyleSpan(Typeface.BOLD)};
            case "textit":
            case "emph":
            case "it":
            case "eng":
                return new Object[]{new StyleSpan(Typeface.ITALIC)};
            case "underline":
                return new Object[]{new UnderlineSpan()};
            case "texttt":
            case "verb":
                return new Object[]{new TypefaceSpan("monospace")};
            case "textsc":
                return new Object[]{new StyleSpan(Typeface.BOLD)};
            case "footnote":
                return new Object[]{new RelativeSizeSpan(0.85f)};
            default:
                return null;
        }
    }

    private void styled(SpannableStringBuilder out, String content, Object... spans) {
        int start = out.length();
        out.append(inline(content));
        int end = out.length();
        for (Object span : spans) {
            out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ---------------------------------------------------------------- parsing

    private static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '%') return line.substring(0, i);
        }
        return line;
    }

    private static int headingLevel(String line) {
        if (line.startsWith("\\section")) return 1;
        if (line.startsWith("\\subsection")) return 2;
        if (line.startsWith("\\subsubsection")) return 3;
        if (line.startsWith("\\chapter")) return 1;
        if (line.startsWith("\\paragraph")) return 3;
        return 0;
    }

    private static String argumentOf(String line) {
        int open = line.indexOf('{');
        int[] argument = open < 0 ? null : braced(line, open);
        return argument == null ? "" : line.substring(argument[0], argument[1]);
    }

    private static String environmentOf(String line, String command) {
        int pos = line.indexOf(command);
        if (pos < 0) return null;
        int[] argument = braced(line, pos + command.length() - 1);
        return argument == null ? null : line.substring(argument[0], argument[1]);
    }

    private static int findCommand(String text, String command, int from) {
        int index = from;
        while (index < text.length()) {
            int pos = text.indexOf(command, index);
            if (pos < 0) return -1;
            int after = pos + command.length();
            if (after >= text.length() || !Character.isLetter(text.charAt(after))) {
                return pos;
            }
            index = after;
        }
        return -1;
    }

    private static int[] braced(String text, int from) {
        int start = text.indexOf('{', from);
        if (start < 0) return null;
        int depth = 0;
        int limit = Math.min(text.length(), start + MAX_SPAN_LOOKAHEAD);
        for (int i = start; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return new int[]{start + 1, i, i + 1};
            }
        }
        return null;
    }

    private static int[] bracedUnlimited(String text, int from) {
        int start = text.indexOf('{', from);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return new int[]{start + 1, i, i + 1};
            }
        }
        return null;
    }

    private static int[] bracketed(String text, int from) {
        if (from >= text.length() || text.charAt(from) != '[') return null;
        int depth = 0;
        int limit = Math.min(text.length(), from + MAX_SPAN_LOOKAHEAD);
        for (int i = from; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return new int[]{from + 1, i, i + 1};
            }
        }
        return null;
    }

    private static int skipWhitespace(String text, int from) {
        int cursor = from;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static void extractBibKeys(String source, Map<String, Integer> target) {
        Matcher matcher = BIBITEM_PATTERN.matcher(source);
        int index = 1;
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            if (!target.containsKey(key)) {
                target.put(key, index++);
            }
        }
    }

    private static Map<String, String> indexReferences(String body, String preamble) {
        Map<String, String> refs = new LinkedHashMap<>();
        boolean tablesWithinSections = preamble != null
                && (preamble.contains("\\numberwithin{table}{section}")
                || preamble.contains("\\counterwithin{table}{section}"));
        int sec = 0;
        int subsec = 0;
        int eq = 0;
        int fig = 0;
        int tab = 0;

        String[] lines = body.split("\n", -1);
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.startsWith("\\section")) {
                sec++;
                subsec = 0;
                eq = 0;
                if (tablesWithinSections) tab = 0;
                String label = extractCommandArgument(line, "\\label");
                if (!label.isEmpty()) refs.put(label, String.valueOf(sec));
            } else if (line.startsWith("\\subsection")) {
                subsec++;
                String label = extractCommandArgument(line, "\\label");
                if (!label.isEmpty()) refs.put(label, sec + "." + subsec);
            } else if (line.startsWith("\\begin{equation}") || line.startsWith("\\begin{align}")) {
                eq++;
            } else if (line.startsWith("\\begin{figure}")) {
                fig++;
            } else if (line.startsWith("\\begin{table}")
                    || line.startsWith("\\begin{longtable}")) {
                tab++;
            }

            if (line.contains("\\label")) {
                String label = extractCommandArgument(line, "\\label");
                if (!label.isEmpty() && !refs.containsKey(label)) {
                    if (label.startsWith("eq:")) {
                        refs.put(label, (sec > 0 ? sec + "." : "") + Math.max(1, eq));
                    } else if (label.startsWith("fig:")) {
                        refs.put(label, String.valueOf(Math.max(1, fig)));
                    } else if (label.startsWith("tab:")) {
                        String number = String.valueOf(Math.max(1, tab));
                        if (tablesWithinSections && sec > 0) number = sec + "." + number;
                        refs.put(label, number);
                    } else if (label.startsWith("sec:")) {
                        refs.put(label, String.valueOf(Math.max(1, sec)));
                    } else {
                        refs.put(label, String.valueOf(Math.max(1, sec)));
                    }
                }
            }
        }
        return refs;
    }

    private static void extractMacros(String text, Map<String, String> vars,
                                      Map<String, ParameterizedMacro> params) {
        Matcher matcher = NEWCOMMAND_START_PATTERN.matcher(text);
        int cursor = 0;
        while (matcher.find(cursor)) {
            int after = matcher.end();
            int[] nameArg = braced(text, after);
            String name = "";
            int nextCursor = after;
            if (nameArg != null) {
                name = text.substring(nameArg[0], nameArg[1]).trim();
                nextCursor = nameArg[2];
            } else {
                nextCursor = skipWhitespace(text, after);
                if (nextCursor < text.length() && text.charAt(nextCursor) == '\\') {
                    int wordEnd = nextCursor + 1;
                    while (wordEnd < text.length() && Character.isLetter(text.charAt(wordEnd))) wordEnd++;
                    name = text.substring(nextCursor, wordEnd);
                    nextCursor = wordEnd;
                }
            }
            if (name.startsWith("\\")) name = name.substring(1);
            if (name.isEmpty()) {
                cursor = matcher.end();
                continue;
            }

            nextCursor = skipWhitespace(text, nextCursor);
            int[] optArg = bracketed(text, nextCursor);
            int paramCount = 0;
            String optionalDefault = null;
            if (optArg != null) {
                try {
                    paramCount = Integer.parseInt(text.substring(optArg[0], optArg[1]).trim());
                } catch (Exception ignored) {}
                nextCursor = optArg[2];
                nextCursor = skipWhitespace(text, nextCursor);
                int[] defaultArg = bracketed(text, nextCursor);
                if (defaultArg != null) {
                    optionalDefault = text.substring(defaultArg[0], defaultArg[1]);
                    nextCursor = defaultArg[2];
                }
            }

            nextCursor = skipWhitespace(text, nextCursor);
            int[] bodyArg = braced(text, nextCursor);
            if (bodyArg != null) {
                String macroBody = text.substring(bodyArg[0], bodyArg[1]);
                if (paramCount > 0) {
                    params.put(name, new ParameterizedMacro(
                            paramCount, macroBody, optionalDefault));
                } else {
                    vars.put(name, macroBody);
                }
                cursor = bodyArg[2];
            } else {
                cursor = matcher.end();
            }
        }
    }

    private static String expandMacros(String body, Map<String, String> vars,
                                       Map<String, ParameterizedMacro> params) {
        String result = body;
        // A bounded number of passes supports macros that expand to other macros without
        // allowing malformed or recursive definitions to hang the editor.
        for (int pass = 0; pass < 8; pass++) {
            String before = result;
            for (Map.Entry<String, ParameterizedMacro> entry : params.entrySet()) {
                // This command has dedicated structural rendering (page break + heading).
                if (entry.getKey().equals("structsection")) continue;
                result = expandParameterizedMacro(result, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                result = result.replace("\\" + entry.getKey() + "{}", entry.getValue());
                result = result.replace("\\" + entry.getKey() + " ", entry.getValue() + " ");
                result = result.replace("\\" + entry.getKey(), entry.getValue());
            }
            if (result.equals(before)) break;
        }
        return result;
    }

    private static String expandParameterizedMacro(String source, String name,
                                                   ParameterizedMacro macro) {
        String command = "\\" + name;
        StringBuilder expanded = new StringBuilder(source.length());
        int cursor = 0;
        while (cursor < source.length()) {
            int found = source.indexOf(command, cursor);
            if (found < 0) {
                expanded.append(source, cursor, source.length());
                break;
            }
            int commandEnd = found + command.length();
            if (commandEnd < source.length()
                    && Character.isLetter(source.charAt(commandEnd))) {
                expanded.append(source, cursor, commandEnd);
                cursor = commandEnd;
                continue;
            }

            int argumentCursor = skipWhitespace(source, commandEnd);
            List<String> arguments = new ArrayList<>();
            if (macro.optionalDefault != null) {
                int[] optional = bracketed(source, argumentCursor);
                if (optional != null) {
                    arguments.add(source.substring(optional[0], optional[1]));
                    argumentCursor = optional[2];
                } else {
                    arguments.add(macro.optionalDefault);
                }
            }

            boolean complete = true;
            while (arguments.size() < macro.paramCount) {
                argumentCursor = skipWhitespace(source, argumentCursor);
                int[] argument = braced(source, argumentCursor);
                if (argument == null) {
                    complete = false;
                    break;
                }
                arguments.add(source.substring(argument[0], argument[1]));
                argumentCursor = argument[2];
            }
            if (!complete) {
                expanded.append(source, cursor, commandEnd);
                cursor = commandEnd;
                continue;
            }

            String replacement = macro.body;
            for (int i = arguments.size(); i >= 1; i--) {
                replacement = replacement.replace("#" + i, arguments.get(i - 1));
            }
            expanded.append(source, cursor, found).append(replacement);
            cursor = argumentCursor;
        }
        return expanded.toString();
    }

    private static final class ParameterizedMacro {
        final int paramCount;
        final String body;
        final String optionalDefault;

        ParameterizedMacro(int paramCount, String body, String optionalDefault) {
            this.paramCount = paramCount;
            this.body = body;
            this.optionalDefault = optionalDefault;
        }
    }

    private static float renderScale(Paint paint, float contentWidth, float liveWidth) {
        if (liveWidth <= 0f || contentWidth <= 0f) return 1.0f;
        return liveWidth / contentWidth;
    }

    private static float naturalRenderScale(Paint paint) {
        return 1.0f;
    }

    // ---------------------------------------------------------------- spans

    public static final class TableCellSpan extends ReplacementSpan {
        private final List<String> cells;
        private final float[] colFractions;
        private final boolean isLandscape;
        private final boolean isHeader;
        private final boolean isFirstColBold;
        private final float fontSize;
        private final int textColor;
        private final int borderColor;
        private final int backgroundColor;
        private final float liveWidth;

        public TableCellSpan(List<String> cells, float[] colFractions, boolean isLandscape,
                             boolean isHeader, boolean isFirstColBold, float fontSize,
                             int textColor, int borderColor, int backgroundColor, float liveWidth) {
            this.cells = cells;
            this.colFractions = colFractions;
            this.isLandscape = isLandscape;
            this.isHeader = isHeader;
            this.isFirstColBold = isFirstColBold;
            this.fontSize = fontSize;
            this.textColor = textColor;
            this.borderColor = borderColor;
            this.backgroundColor = backgroundColor;
            this.liveWidth = liveWidth;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            float width = isLandscape ? 746f : 499f;
            float scale = renderScale(paint, width, liveWidth);
            float padding = 5f;
            float maxH = 20f;

            TextPaint cp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            cp.setTextSize(fontSize);
            cp.setTypeface(Typeface.SERIF);
            cp.setColor(textColor);

            for (int i = 0; i < cells.size(); i++) {
                float frac = i < colFractions.length ? colFractions[i] : (1.0f / cells.size());
                float cellW = width * frac;
                float innerW = Math.max(10f, cellW - (padding * 2f));
                String raw = cells.get(i);
                StaticLayout layout = new StaticLayout(raw, cp, (int) innerW,
                        Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false);
                float cellH = layout.getHeight() + (padding * 2f);
                if (cellH > maxH) maxH = cellH;
            }

            int h = Math.round(maxH * scale);
            if (fm != null) {
                fm.top = -h;
                fm.ascent = -h;
                fm.descent = 0;
                fm.bottom = 0;
            }
            return Math.round(width * scale);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            float width = isLandscape ? 746f : 499f;
            float scale = renderScale(paint, width, liveWidth);
            float padding = 5f;
            float baseHeight = (bottom - top) / scale;
            float baseBottom = top + baseHeight;

            canvas.save();
            canvas.translate(x * (1f - scale), top * (1f - scale));
            canvas.scale(scale, scale);

            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(0.8f);
            strokePaint.setColor(borderColor);

            if (backgroundColor != 0) {
                Paint bgPaint = new Paint();
                bgPaint.setStyle(Paint.Style.FILL);
                bgPaint.setColor(backgroundColor);
                canvas.drawRect(x, top, x + width, baseBottom, bgPaint);
            }

            TextPaint cellPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            cellPaint.setTextSize(fontSize);
            cellPaint.setColor(textColor);
            cellPaint.setTypeface(isHeader ? Typeface.create(Typeface.SERIF, Typeface.BOLD) : Typeface.SERIF);

            float curX = x;
            for (int i = 0; i < cells.size(); i++) {
                float frac = i < colFractions.length ? colFractions[i] : (1.0f / cells.size());
                float cellW = width * frac;
                canvas.drawRect(curX, top, curX + cellW, baseBottom, strokePaint);

                TextPaint tp = cellPaint;
                if (!isHeader && isFirstColBold && i == 0) {
                    tp = new TextPaint(cellPaint);
                    tp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                }

                String raw = cells.get(i);
                float innerW = Math.max(10f, cellW - (padding * 2f));
                StaticLayout layout = new StaticLayout(raw, tp, (int) innerW,
                        Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false);

                canvas.save();
                canvas.translate(curX + padding, top + padding);
                layout.draw(canvas);
                canvas.restore();

                curX += cellW;
            }
            canvas.restore();
        }
    }

    public static final class TikzFigureSpan extends ReplacementSpan {
        private final float contentWidth;
        private final float height;
        private final String tikzSource;
        private final String caption;
        private final float liveWidth;

        public TikzFigureSpan(float contentWidth, float height, String tikzSource, String caption, float liveWidth) {
            this.contentWidth = contentWidth;
            this.height = height;
            this.tikzSource = tikzSource;
            this.caption = caption;
            this.liveWidth = liveWidth;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            float scale = renderScale(paint, contentWidth, liveWidth);
            int h = Math.round(height * scale);
            if (fm != null) {
                fm.top = -h;
                fm.ascent = -h;
                fm.descent = 0;
                fm.bottom = 0;
            }
            return Math.round(contentWidth * scale);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            float scale = renderScale(paint, contentWidth, liveWidth);
            canvas.save();
            canvas.translate(x * (1f - scale), top * (1f - scale));
            canvas.scale(scale, scale);

            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.2f);
            stroke.setColor(0xFF333333);

            Paint fillBlue = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillBlue.setStyle(Paint.Style.FILL);
            fillBlue.setColor(0xFFE8F0FE);

            Paint fillRed = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillRed.setStyle(Paint.Style.FILL);
            fillRed.setColor(0xFFFCE8E6);

            TextPaint nodePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            nodePaint.setTextSize(13.0f);
            nodePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            nodePaint.setColor(0xFF111111);
            nodePaint.setTextAlign(Paint.Align.CENTER);

            float midX = x + contentWidth / 2f;
            float cy = top + 45f;
            float r = 36f;

            // Draw Venn/Overlap Circles from TikZ
            canvas.drawCircle(midX - 35f, cy, r, fillBlue);
            canvas.drawCircle(midX - 35f, cy, r, stroke);

            canvas.drawCircle(midX + 35f, cy, r, fillRed);
            canvas.drawCircle(midX + 35f, cy, r, stroke);

            canvas.drawText("Markdown", midX - 42f, cy + 4.5f, nodePaint);
            canvas.drawText("LaTeX", midX + 42f, cy + 4.5f, nodePaint);

            TextPaint centerPaint = new TextPaint(nodePaint);
            centerPaint.setColor(0xFF0B57D0);
            canvas.drawText("Shard", midX, cy + 4.5f, centerPaint);

            if (!caption.isEmpty()) {
                TextPaint cp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                cp.setTextSize(11.5f);
                cp.setTypeface(Typeface.SERIF);
                cp.setColor(0xFF444444);
                StaticLayout captionLayout = new StaticLayout(
                        caption, cp, Math.max(1, Math.round(contentWidth - 16f)),
                        Layout.Alignment.ALIGN_CENTER, 1.0f, 0f, false);
                canvas.save();
                canvas.translate(x + 8f, top + 98f);
                captionLayout.draw(canvas);
                canvas.restore();
            }

            canvas.restore();
        }
    }

    public static final class EquationSpan extends ReplacementSpan {
        private final String rawMath;
        private final String eqNum;
        private final float contentWidth;
        private final float liveWidth;

        public EquationSpan(String rawMath, String eqNum, float contentWidth, float liveWidth) {
            this.rawMath = rawMath;
            this.eqNum = eqNum;
            this.contentWidth = contentWidth;
            this.liveWidth = liveWidth;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            float scale = renderScale(paint, contentWidth, liveWidth);
            String[] lines = rawMath.split("\\\\\\\\|\n");
            int lineCount = Math.max(1, lines.length);
            int h = Math.round((lineCount * 22 + 14) * scale);
            if (fm != null) {
                fm.top = -h;
                fm.ascent = -h;
                fm.descent = 0;
                fm.bottom = 0;
            }
            return Math.round(contentWidth * scale);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            float scale = renderScale(paint, contentWidth, liveWidth);
            float baseHeight = (bottom - top) / scale;
            canvas.save();
            canvas.translate(x * (1f - scale), top * (1f - scale));
            canvas.scale(scale, scale);

            TextPaint mathPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            mathPaint.setTextSize(13.5f);
            mathPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
            mathPaint.setColor(0xFF111111);
            mathPaint.setTextAlign(Paint.Align.CENTER);

            TextPaint numPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            numPaint.setTextSize(13.0f);
            numPaint.setTypeface(Typeface.SERIF);
            numPaint.setColor(0xFF333333);
            numPaint.setTextAlign(Paint.Align.RIGHT);

            float midX = x + contentWidth / 2f;
            float midY = top + baseHeight / 2f;

            if (!eqNum.isEmpty()) {
                canvas.drawText(eqNum, x + contentWidth - 6f, midY + 4.5f, numPaint);
            }

            String[] rawLines = rawMath.split("\\\\\\\\|\n");
            if (rawLines.length == 1) {
                String formatted = formatMath(rawLines[0].replace("&", ""));
                canvas.drawText(formatted, midX, midY + 4.5f, mathPaint);
            } else {
                float lineH = 22f;
                float startY = top + (baseHeight - (rawLines.length * lineH)) / 2f + 16f;
                for (int i = 0; i < rawLines.length; i++) {
                    String clean = formatMath(rawLines[i].replace("&", ""));
                    if (!clean.isEmpty()) {
                        canvas.drawText(clean, midX, startY + (i * lineH), mathPaint);
                    }
                }
            }
            canvas.restore();
        }
    }

    public static final class TocRowSpan extends ReplacementSpan {
        private final String prefix;
        private final String title;
        private final String pageNum;
        private final boolean isMajor;
        private final int level;
        private final float contentWidth;
        private final float liveWidth;

        public TocRowSpan(String prefix, String title, String pageNum, boolean isMajor,
                          int level, float contentWidth, float liveWidth) {
            this.prefix = prefix;
            this.title = title;
            this.pageNum = pageNum;
            this.isMajor = isMajor;
            this.level = level;
            this.contentWidth = contentWidth;
            this.liveWidth = liveWidth;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            float scale = renderScale(paint, contentWidth, liveWidth);
            TextPaint tp = createTextPaint();
            int titleWidth = Math.max(1, Math.round(availableTitleWidth(tp)));
            StaticLayout titleLayout = new StaticLayout(prefix + title, tp, titleWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false);
            int h = Math.round((titleLayout.getHeight() + (isMajor ? 4 : 2)) * scale);
            if (fm != null) {
                fm.top = -h;
                fm.ascent = -h;
                fm.descent = 0;
                fm.bottom = 0;
            }
            return Math.round(contentWidth * scale);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            float scale = renderScale(paint, contentWidth, liveWidth);
            canvas.save();
            canvas.translate(x * (1f - scale), top * (1f - scale));
            canvas.scale(scale, scale);
            TextPaint tp = createTextPaint();
            float leftX = x + (level == 2 ? 18f : (level == 3 ? 32f : 0f));
            String fullTitle = prefix + title;
            float pageW = tp.measureText(pageNum);
            float rightX = x + contentWidth - pageW - 6f;
            int titleWidth = Math.max(1, Math.round(availableTitleWidth(tp)));
            StaticLayout titleLayout = new StaticLayout(fullTitle, tp, titleWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false);

            canvas.save();
            canvas.translate(leftX, top + (isMajor ? 2f : 1f));
            titleLayout.draw(canvas);
            canvas.restore();

            float baseline = top + titleLayout.getLineBaseline(titleLayout.getLineCount() - 1);
            canvas.drawText(pageNum, rightX, baseline, tp);

            if (titleLayout.getLineCount() == 1) {
                float dotStart = leftX + tp.measureText(fullTitle) + 8f;
                float dotEnd = rightX - 8f;
                for (float dx = dotStart; dx < dotEnd; dx += 7.5f) {
                    canvas.drawText(".", dx, baseline, tp);
                }
            }
            canvas.restore();
        }

        private TextPaint createTextPaint() {
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTextSize(13.0f);
            tp.setColor(0xFF111111);
            tp.setTypeface(isMajor ? Typeface.create(Typeface.SERIF, Typeface.BOLD) : Typeface.SERIF);
            return tp;
        }

        private float availableTitleWidth(TextPaint tp) {
            float indent = level == 2 ? 18f : (level == 3 ? 32f : 0f);
            return Math.max(1f, contentWidth - indent - tp.measureText(pageNum) - 14f);
        }
    }

    public static final class VerticalSpaceSpan extends ReplacementSpan {
        private final float em;

        public VerticalSpaceSpan(float em) {
            this.em = em;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fontMetrics) {
            if (fontMetrics != null) {
                int height = Math.max(1, Math.round(paint.getTextSize() * em));
                fontMetrics.top = -height;
                fontMetrics.ascent = -height;
                fontMetrics.descent = 0;
                fontMetrics.bottom = 0;
            }
            return 0;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
        }
    }

    public static final class LongtableRepeatHeaderSpan extends ReplacementSpan {
        private final CharSequence header;
        private final boolean repeatOnFirstPage;

        public LongtableRepeatHeaderSpan(CharSequence header, boolean repeatOnFirstPage) {
            this.header = new SpannableStringBuilder(header);
            this.repeatOnFirstPage = repeatOnFirstPage;
        }

        public CharSequence getHeader() {
            return new SpannableStringBuilder(header);
        }

        public boolean repeatsOnFirstPage() {
            return repeatOnFirstPage;
        }

        public LongtableRepeatHeaderSpan withHeader(CharSequence resolvedHeader) {
            return new LongtableRepeatHeaderSpan(resolvedHeader, repeatOnFirstPage);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fontMetrics) {
            return 0;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
        }
    }

    public static final class MarkerSpan extends ReplacementSpan {
        private final String preview;
        private final int color;

        public MarkerSpan(String preview, int color) {
            this.preview = preview;
            this.color = color;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fontMetrics) {
            return preview.isEmpty() ? 0 : Math.round(paint.measureText(preview));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            if (preview.isEmpty()) return;
            int previousColor = paint.getColor();
            paint.setColor(color);
            canvas.drawText(preview, x, y, paint);
            paint.setColor(previousColor);
        }
    }
}
