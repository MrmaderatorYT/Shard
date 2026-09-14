package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Zig extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "align", "allowzero", "and", "anyframe", "anytype", "asm",
        "async", "await", "break", "catch", "comptime", "const",
        "continue", "defer", "else", "enum", "errdefer", "error",
        "export", "extern", "false", "fn", "for", "if", "inline",
        "noalias", "nosuspend", "null", "or", "orelse", "packed",
        "pub", "resume", "return", "struct", "suspend", "switch",
        "test", "threadlocal", "true", "try", "undefined", "union",
        "unreachable", "var", "volatile", "while"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "bool", "f16", "f32", "f64", "f128", "i8", "i16", "i32", "i64",
        "i128", "u8", "u16", "u32", "u64", "u128", "isize", "usize",
        "c_char", "c_short", "c_ushort", "c_int", "c_uint", "c_long",
        "c_ulong", "c_longlong", "c_ulonglong", "c_longdouble",
        "void", "noreturn", "type", "anyerror", "anyopaque", "promise",
        "usize", "isize"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null", "undefined", "error", "undefined",
        "maxInt", "minInt", "pi", "e"
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
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'|'\\\\x[0-9a-fA-F]+'"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }

    @Override
    protected String getFunctionPattern() { return "\\b(fn)\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
