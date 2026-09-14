package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class Highlighter {

    protected HighlightTheme theme = HighlightTheme.VS_CODE_DARK;

    protected static final int COLOR_KEYWORD = 0xFF6A9955;
    protected static final int COLOR_STRING = 0xFFCE9178;
    protected static final int COLOR_NUMBER = 0xFFB5CEA8;
    protected static final int COLOR_COMMENT = 0xFF6A9955;
    protected static final int COLOR_TYPE = 0xFF4EC9B0;
    protected static final int COLOR_FUNCTION = 0xFFDCDCAA;
    protected static final int COLOR_ANNOTATION = 0xFFD4D4D4;
    protected static final int COLOR_CONSTANT = 0xFF4FC1FF;
    protected static final int COLOR_DEFAULT = 0xFFD4D4D4;
    protected static final int COLOR_PREPROCESSOR = 0xFFC586C0;
    protected static final int COLOR_TAG = 0xFF569CD6;
    protected static final int COLOR_ATTR = 0xFF9CDCFE;
    protected static final int COLOR_OPERATOR = 0xFFD4D4D4;
    protected static final int COLOR_PARAM = 0xFF9CDCFE;

    public void setTheme(HighlightTheme theme) {
        this.theme = theme;
    }

    protected abstract Set<String> getKeywords();
    protected abstract Set<String> getTypes();
    protected abstract Set<String> getConstants();
    protected abstract String getLineCommentPattern();
    protected abstract String getBlockCommentStart();
    protected abstract String getBlockCommentEnd();
    protected abstract String getStringPattern();

    public SpannableStringBuilder highlight(String code) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        applyHighlights(sb);
        return sb;
    }

    protected void applyHighlights(SpannableStringBuilder sb) {
        String code = sb.toString();

        applyPattern(sb, code, getLineCommentPattern(), theme.comment, false);
        applyPattern(sb, code, getBlockCommentPattern(), theme.comment, false);
        applyPattern(sb, code, getStringPattern(), theme.string, false);
        applyPattern(sb, code, getCharPattern(), theme.string, false);
        applyPattern(sb, code, getNumberPattern(), theme.number, false);
        applyPattern(sb, code, getAnnotationPattern(), theme.annotation, false);

        applyKeywords(sb, code, getKeywords(), theme.keyword, Typeface.BOLD);
        applyKeywords(sb, code, getTypes(), theme.type, Typeface.NORMAL);
        applyKeywords(sb, code, getConstants(), theme.constant, Typeface.NORMAL);
        applyWordPattern(sb, code, getFunctionPattern(), theme.function, Typeface.NORMAL);
    }

    protected void applyPattern(SpannableStringBuilder sb, String code,
                                String regex, int color, boolean bold) {
        if (regex == null || regex.isEmpty()) return;
        applyPattern(sb, code, Pattern.compile(regex, Pattern.MULTILINE), color, bold);
    }

    protected void applyPattern(SpannableStringBuilder sb, String code,
                                Pattern pattern, int color, boolean bold) {
        if (pattern == null) return;
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            sb.setSpan(new ForegroundColorSpan(color), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    protected void applyKeywords(SpannableStringBuilder sb, String code,
                                 Set<String> keywords, int color, int style) {
        if (keywords == null || keywords.isEmpty()) return;
        StringBuilder sbRegex = new StringBuilder("\\b(");
        boolean first = true;
        for (String kw : keywords) {
            if (!first) sbRegex.append("|");
            sbRegex.append(Pattern.quote(kw));
            first = false;
        }
        sbRegex.append(")\\b");
        Pattern pattern = Pattern.compile(sbRegex.toString());
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            sb.setSpan(new ForegroundColorSpan(color), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (style != Typeface.NORMAL) {
                sb.setSpan(new StyleSpan(style), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    protected void applyWordPattern(SpannableStringBuilder sb, String code,
                                    String regex, int color, int style) {
        if (regex == null || regex.isEmpty()) return;
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            sb.setSpan(new ForegroundColorSpan(color), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    protected String getBlockCommentPattern() {
        String start = getBlockCommentStart();
        String end = getBlockCommentEnd();
        if (start == null || end == null) return null;
        return Pattern.quote(start) + "[\\s\\S]*?" + Pattern.quote(end);
    }

    protected String getCharPattern() {
        return "'[^'\\\\]'";
    }

    protected String getNumberPattern() {
        return "\\b\\d+\\.?\\d*([eE][+-]?\\d+)?[fFlLdD]?\\b|\\b0[xX][0-9a-fA-F]+[lL]?\\b|\\b0[bB][01]+[lL]?\\b";
    }

    protected String getAnnotationPattern() {
        return null;
    }

    protected String getFunctionPattern() {
        return "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()";
    }
}
