package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Json extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>();

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "null", "true", "false"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "null", "true", "false"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return null; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\""; }

    @Override
    protected String getNumberPattern() { return "-?\\d+\\.?\\d*([eE][+-]?\\d+)?"; }

    @Override
    protected String getAnnotationPattern() { return null; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        String code = sb.toString();
        applyPattern(sb, code, "\"([^\"]*)\"\\s*:", COLOR_ATTR, false);
        applyPattern(sb, code, "\"[^\"]*\"", COLOR_STRING, false);
        applyPattern(sb, code, getNumberPattern(), COLOR_NUMBER, false);
        applyPattern(sb, code, "\\bnull\\b", COLOR_CONSTANT, false);
        applyPattern(sb, code, "\\btrue\\b", COLOR_CONSTANT, false);
        applyPattern(sb, code, "\\bfalse\\b", COLOR_CONSTANT, false);
    }
}
