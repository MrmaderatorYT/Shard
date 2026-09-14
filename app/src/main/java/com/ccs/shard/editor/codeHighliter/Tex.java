package com.ccs.shard.editor.codeHighliter;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Syntax highlighter for TeX and LaTeX sources. */
public final class Tex extends Highlighter {

    private static final Set<String> NONE = Collections.emptySet();
    private static final Pattern ENVIRONMENT = Pattern.compile(
            "\\\\(?:begin|end)\\{([^}\\r\\n]+)\\}");

    private static final Pattern PAT_DISPLAY_MATH = Pattern.compile("(?<!\\\\)\\$\\$[\\s\\S]*?(?<!\\\\)\\$\\$", Pattern.MULTILINE);
    private static final Pattern PAT_INLINE_MATH = Pattern.compile("(?<!\\\\)\\$(?!\\$)(?:\\\\.|[^$\\r\\n])*?(?<!\\\\)\\$(?!\\$)", Pattern.MULTILINE);
    private static final Pattern PAT_BRACKET_MATH = Pattern.compile("\\\\\\[[\\s\\S]*?\\\\\\]", Pattern.MULTILINE);
    private static final Pattern PAT_PAREN_MATH = Pattern.compile("\\\\\\([\\s\\S]*?\\\\\\)", Pattern.MULTILINE);
    private static final Pattern PAT_VERB = Pattern.compile("\\\\verb\\*?(.)(?:(?!\\1).)*\\1", Pattern.MULTILINE);
    private static final Pattern PAT_VERBATIM = Pattern.compile("\\\\begin\\{verbatim\\*?\\}[\\s\\S]*?\\\\end\\{verbatim\\*?\\}", Pattern.MULTILINE);
    private static final Pattern PAT_OPTION = Pattern.compile("\\[[^]\\r\\n]*\\]", Pattern.MULTILINE);
    private static final Pattern PAT_NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b", Pattern.MULTILINE);
    private static final Pattern PAT_OP1 = Pattern.compile("[&_\\^]", Pattern.MULTILINE);
    private static final Pattern PAT_OP2 = Pattern.compile("[{}\\[\\]()]", Pattern.MULTILINE);
    private static final Pattern PAT_COMMAND = Pattern.compile("\\\\(?:[A-Za-z@]+\\*?|.)", Pattern.MULTILINE);
    private static final Pattern PAT_DOLLAR = Pattern.compile("(?<!\\\\)(?:\\$\\$|\\$)", Pattern.MULTILINE);

    @Override protected Set<String> getKeywords() { return NONE; }
    @Override protected Set<String> getTypes() { return NONE; }
    @Override protected Set<String> getConstants() { return NONE; }
    @Override protected String getLineCommentPattern() { return null; }
    @Override protected String getBlockCommentStart() { return null; }
    @Override protected String getBlockCommentEnd() { return null; }
    @Override protected String getStringPattern() { return null; }
    @Override protected String getAnnotationPattern() { return null; }
    @Override protected String getFunctionPattern() { return null; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        String source = sb.toString();

        // Math regions get a base color; commands and operators inside them are
        // layered afterwards so formulas remain easy to scan.
        applyPattern(sb, source, PAT_DISPLAY_MATH, theme.string, false);
        applyPattern(sb, source, PAT_INLINE_MATH, theme.string, false);
        applyPattern(sb, source, PAT_BRACKET_MATH, theme.string, false);
        applyPattern(sb, source, PAT_PAREN_MATH, theme.string, false);

        applyPattern(sb, source, PAT_VERB, theme.string, false);
        applyPattern(sb, source, PAT_VERBATIM, theme.string, false);
        applyPattern(sb, source, PAT_OPTION, theme.attr, false);
        applyPattern(sb, source, PAT_NUMBER, theme.number, false);
        applyPattern(sb, source, PAT_OP1, theme.operator, false);
        applyPattern(sb, source, PAT_OP2, theme.operator, false);

        applyPattern(sb, source, PAT_COMMAND, theme.keyword, true);
        applyEnvironmentNames(sb, source);
        applyPattern(sb, source, PAT_DOLLAR, theme.preprocessor, true);

        // Comments are last so TeX-looking text after an unescaped % stays a comment.
        applyComments(sb, source);
    }

    private void applyEnvironmentNames(SpannableStringBuilder sb, String source) {
        Matcher matcher = ENVIRONMENT.matcher(source);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            sb.setSpan(new ForegroundColorSpan(theme.type), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void applyComments(SpannableStringBuilder sb, String source) {
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = source.length();
            for (int i = lineStart; i < lineEnd; i++) {
                if (source.charAt(i) != '%' || isEscaped(source, i)) continue;
                sb.setSpan(new ForegroundColorSpan(theme.comment), i, lineEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), i, lineEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            }
            lineStart = lineEnd + 1;
        }
    }

    private static boolean isEscaped(String source, int position) {
        int slashes = 0;
        for (int i = position - 1; i >= 0 && source.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) == 1;
    }
}
