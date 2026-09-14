package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Go extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "break", "case", "chan", "const", "continue", "default", "defer", "else",
        "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
        "map", "package", "range", "return", "select", "struct", "switch", "type",
        "var"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "bool", "byte", "complex64", "complex128", "error", "float32", "float64",
        "int", "int8", "int16", "int32", "int64", "rune", "string", "uint",
        "uint8", "uint16", "uint32", "uint64", "uintptr", "any", "comparable",
        "iota", "nil", "true", "false"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "nil", "iota"
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
    protected String getStringPattern() { return "`[^`]*`|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getFunctionPattern() { return "\\bfunc\\s+\\(?[^)]*\\)?\\s*([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
