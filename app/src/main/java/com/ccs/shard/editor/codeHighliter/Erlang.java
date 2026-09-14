package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Erlang extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "after", "begin", "case", "catch", "end", "fun", "if", "let",
        "of", "receive", "try", "when", "andalso", "orelse", "not",
        "bnot", "band", "bor", "bxor", "bsl", "bsr", "div", "rem"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "atom", "binary", "bitstring", "boolean", "byte", "char",
        "float", "function", "integer", "list", "number", "pid",
        "port", "reference", "string", "tuple", "map", "record"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "undefined", "nil", "ok", "error"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "%.*"; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "\\?[a-zA-Z_][a-zA-Z0-9_]*|\\$[a-zA-Z]"; }
}
