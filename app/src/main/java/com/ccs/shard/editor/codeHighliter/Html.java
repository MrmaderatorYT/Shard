package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Html extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>();

    private static final Set<String> TYPES = new HashSet<>();

    private static final Set<String> CONSTANTS = new HashSet<>();

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return null; }

    @Override
    protected String getBlockCommentStart() { return "<!--"; }

    @Override
    protected String getBlockCommentEnd() { return "-->"; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\"|'[^']*'"; }

    @Override
    protected String getAnnotationPattern() { return null; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        String code = sb.toString();
        applyPattern(sb, code, "<!--[\\s\\S]*?-->", COLOR_COMMENT, false);
        applyPattern(sb, code, "<!DOCTYPE\\s+[a-zA-Z]+>", COLOR_PREPROCESSOR, false);
        applyPattern(sb, code, "</?[a-zA-Z][a-zA-Z0-9]*", COLOR_TAG, false);
        applyPattern(sb, code, "/?>", COLOR_TAG, false);
        applyPattern(sb, code, "[a-zA-Z-]+(?==)", COLOR_ATTR, false);
        applyPattern(sb, code, "\"[^\"]*\"", COLOR_STRING, false);
        applyPattern(sb, code, "'[^']*'", COLOR_STRING, false);
        applyPattern(sb, code, "&[a-zA-Z]+;?", COLOR_NUMBER, false);
        applyPattern(sb, code, "&#\\d+;?", COLOR_NUMBER, false);
        applyPattern(sb, code, "&#x[0-9a-fA-F]+;?", COLOR_NUMBER, false);
    }
}
