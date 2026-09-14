package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Css extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "important", "charset", "import", "media", "keyframes", "font-face",
        "supports", "page", "namespace", "document", "charset", "supports",
        "viewport", "counter-style", "font-feature-values"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "inherit", "initial", "unset", "revert", "auto", "none", "normal",
        "block", "inline", "flex", "grid", "table", "absolute", "relative",
        "fixed", "sticky", "static", "hidden", "visible", "scroll",
        "center", "left", "right", "top", "bottom"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "important", "!important"
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
    protected String getBlockCommentStart() { return "/*"; }

    @Override
    protected String getBlockCommentEnd() { return "*/"; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\"|'[^']*'"; }

    @Override
    protected String getNumberPattern() { return "\\d+\\.?\\d*(px|em|rem|%|vh|vw|vmin|vmax|pt|pc|in|cm|mm|ex|ch|fr|s|ms|deg|rad|grad)?"; }

    @Override
    protected String getAnnotationPattern() { return "#[0-9a-fA-F]{3,8}"; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        String code = sb.toString();
        applyPattern(sb, code, "/\\*[\\s\\S]*?\\*/", COLOR_COMMENT, false);
        applyPattern(sb, code, "\"[^\"]*\"|'[^']*'", COLOR_STRING, false);
        applyPattern(sb, code, "#[0-9a-fA-F]{3,8}", COLOR_NUMBER, false);
        applyPattern(sb, code, "\\.[a-zA-Z_-][a-zA-Z0-9_-]*", COLOR_FUNCTION, false);
        applyPattern(sb, code, "@[a-zA-Z_-][a-zA-Z0-9_-]*", COLOR_PREPROCESSOR, false);
        applyPattern(sb, code, getNumberPattern(), COLOR_NUMBER, false);
        applyPattern(sb, code, "!important", COLOR_CONSTANT, true);
    }
}
