package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class R extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "break", "next", "return", "function", "if", "else", "for", "while",
        "repeat", "in", "switch", "case", "default", "try", "catch", "finally",
        "stop", "warning", "message", "library", "require", "source"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "NULL", "NA", "NaN", "Inf", "TRUE", "FALSE", "logical", "integer",
        "double", "numeric", "complex", "character", "raw", "list", "data.frame",
        "matrix", "array", "vector", "factor", "function", "environment",
        "symbol", "expression", "call", "name", "promise"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "TRUE", "FALSE", "NULL", "NA", "NaN", "Inf", "T", "F"
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
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^']*'"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_.]*"; }

    @Override
    protected String getFunctionPattern() { return "\\b([a-zA-Z_][a-zA-Z0-9_.]*)\\s*(?=\\()"; }
}
