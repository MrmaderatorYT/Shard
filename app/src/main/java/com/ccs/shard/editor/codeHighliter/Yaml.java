package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Yaml extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "true", "false", "yes", "no", "on", "off", "null", "nil"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "str", "int", "float", "bool", "list", "dict", "null"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "yes", "no", "on", "off", "null", "nil",
        "~", ".nan", ".inf", "-.inf"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "#.*"; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\"|'[^']*'"; }

    @Override
    protected String getAnnotationPattern() { return "&[a-zA-Z_][a-zA-Z0-9_]*|\\*[a-zA-Z_][a-zA-Z0-9_]*"; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        String code = sb.toString();
        applyPattern(sb, code, "#.*", COLOR_COMMENT, false);
        applyPattern(sb, code, "\"[^\"]*\"", COLOR_STRING, false);
        applyPattern(sb, code, "'[^']*'", COLOR_STRING, false);
        applyPattern(sb, code, ":[^\\s]+", COLOR_ATTR, false);
        applyPattern(sb, code, "\\b(true|false|yes|no|on|off|null|nil)\\b", COLOR_CONSTANT, false);
        applyPattern(sb, code, "-?\\d+\\.?\\d*([eE][+-]?\\d+)?", COLOR_NUMBER, false);
        applyPattern(sb, code, "&[a-zA-Z_][a-zA-Z0-9_]*", COLOR_KEYWORD, false);
        applyPattern(sb, code, "\\*[a-zA-Z_][a-zA-Z0-9_]*", COLOR_KEYWORD, false);
    }
}
