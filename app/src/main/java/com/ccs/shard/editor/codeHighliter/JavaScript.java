package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class JavaScript extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "finally",
        "for", "function", "if", "import", "in", "instanceof", "let", "new",
        "of", "return", "static", "super", "switch", "this", "throw", "try",
        "typeof", "var", "void", "while", "with", "yield", "async", "await",
        "from", "as", "enum", "implements", "interface", "package", "private",
        "protected", "public", "abstract", "boolean", "byte", "char", "double",
        "final", "float", "goto", "int", "long", "native", "short",
        "synchronized", "throws", "transient", "volatile"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Array", "Boolean", "Date", "Error", "Function", "JSON", "Map", "Math",
        "Number", "Object", "Promise", "Proxy", "RegExp", "Set", "String",
        "Symbol", "WeakMap", "WeakSet", "Intl", "console", "document", "window",
        "navigator", "globalThis"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null", "undefined", "NaN", "Infinity"
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
    protected String getStringPattern() { return "`[^`\\\\]*(\\\\.[^`\\\\]*)*`|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(\\\\.[^'\\\\]*)*'"; }

    @Override
    protected String getFunctionPattern() { return "\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*(?=\\()"; }
}
