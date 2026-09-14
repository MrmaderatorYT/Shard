package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Lua extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "math", "string", "table", "io", "os", "coroutine", "debug",
        "package", "loadlib", "rawget", "rawset", "setmetatable", "getmetatable",
        "print", "type", "tostring", "tonumber", "error", "assert", "pcall",
        "xpcall", "select", "unpack", "next", "pairs", "ipairs", "rawequal",
        "rawlen", "collectgarbage", "require", "dofile", "loadfile", "load",
        "setfenv", "getfenv", "module", "module", "setmetatable", "getmetatable"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "nil", "_G", "_ENV", "_VERSION", "self"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "--.*"; }

    @Override
    protected String getBlockCommentStart() { return "--[["; }

    @Override
    protected String getBlockCommentEnd() { return "]]"; }

    @Override
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(\\\\.[^'\\\\]*)*'"; }

    @Override
    protected String getFunctionPattern() { return "\\bfunction\\s+([a-zA-Z_][a-zA-Z0-9_.]*)"; }
}
