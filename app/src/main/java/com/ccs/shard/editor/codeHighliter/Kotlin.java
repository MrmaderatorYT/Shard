package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Kotlin extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
        "if", "in", "interface", "is", "null", "object", "package", "return",
        "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
        "var", "when", "while", "by", "catch", "constructor", "delegate",
        "dynamic", "enum", "expect", "actual", "final", "finally", "get", "import",
        "init", "inner", "internal", "lateinit", "noinline", "out", "override",
        "private", "protected", "public", "reified", "sealed", "set", "suspend",
        "tailrec", "vararg", "companion", "data", "inline", "it", "crossinline",
        "annotation", "value"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Any", "Array", "Boolean", "Byte", "Char", "Double", "Float", "Int",
        "Long", "Nothing", "Short", "String", "Unit", "List", "Map", "Set",
        "MutableList", "MutableMap", "MutableSet", "Pair", "Triple",
        "Sequence", "Iterable", "Collection", "Comparable", "Enum", "Annotation"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "//.*"; }

    @Override
    protected String getBlockCommentStart() { return "/*"; }

    @Override
    protected String getBlockCommentEnd() { return "*/"; }

    @Override
    protected String getStringPattern() { return "\"\"\"[\\s\\S]*?\"\"\"|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }
}
