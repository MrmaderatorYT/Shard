package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Python extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "and", "as", "assert", "async", "await", "break", "class", "continue",
        "def", "del", "elif", "else", "except", "finally", "for", "from",
        "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
        "or", "pass", "raise", "return", "try", "while", "with", "yield",
        "match", "case"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "int", "float", "str", "bool", "list", "dict", "set", "tuple",
        "bytes", "type", "object", "None", "True", "False", "range",
        "enumerate", "zip", "map", "filter", "len", "print", "input",
        "open", "super", "property", "staticmethod", "classmethod"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "True", "False", "None", "__name__", "__main__", "__init__",
        "__str__", "__repr__", "__len__", "__getitem__", "__setitem__"
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
    protected String getStringPattern() { return "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(\\\\.[^'\\\\]*)*'"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_.]*"; }

    @Override
    protected String getFunctionPattern() { return "\\b(def|async\\s+def)\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        super.applyHighlights(sb);
        applyPattern(sb, sb.toString(), "#.*", COLOR_COMMENT, false);
    }
}
