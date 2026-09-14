package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Dart extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "as", "assert", "async", "await", "break", "case", "catch",
        "class", "const", "continue", "covariant", "default", "deferred", "do",
        "dynamic", "else", "enum", "export", "extends", "extension", "external",
        "factory", "false", "final", "finally", "for", "function", "get", "if",
        "implements", "import", "in", "interface", "is", "late", "let", "library",
        "mixin", "new", "null", "on", "operator", "part", "required", "rethrow",
        "return", "set", "show", "static", "super", "switch", "sync", "this",
        "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "int", "double", "num", "String", "bool", "List", "Map", "Set", "Dynamic",
        "Object", "Function", "Future", "Stream", "Iterable", "Null", "Never",
        "Record", "Type", "Symbol", "Comparable", "Enum", "Iterable"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null", "this", "super"
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
    protected String getStringPattern() { return "\"\"\"[\\s\\S]*?\"\"\"|'[^'\\\\]*(\\\\.[^'\\\\]*)*'|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }
}
