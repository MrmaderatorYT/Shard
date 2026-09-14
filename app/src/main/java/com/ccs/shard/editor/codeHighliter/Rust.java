package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Rust extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn",
        "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
        "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
        "self", "Self", "static", "struct", "super", "trait", "true", "type",
        "unsafe", "use", "where", "while", "yield"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "bool", "char", "f32", "f64", "i8", "i16", "i32", "i64", "i128", "isize",
        "str", "String", "u8", "u16", "u32", "u64", "u128", "usize",
        "Vec", "HashMap", "HashSet", "Box", "Rc", "Arc", "Cell", "RefCell",
        "Option", "Result", "Some", "None", "Ok", "Err", "Cow", "Pin",
        "Future", "Stream", "Iterator", "IntoIterator", "Display", "Debug",
        "Clone", "Copy", "Default", "From", "Into", "TryFrom", "TryInto"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "None", "Some", "Ok", "Err", "Self"
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
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'|'\\\\u[0-9a-fA-F]+'|'\\\\x[0-9a-fA-F]+'"; }

    @Override
    protected String getAnnotationPattern() { return "#\\[([^\\]]*)\\]|#!\\[([^\\]]*)\\]"; }

    @Override
    protected String getFunctionPattern() { return "\\bfn\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
