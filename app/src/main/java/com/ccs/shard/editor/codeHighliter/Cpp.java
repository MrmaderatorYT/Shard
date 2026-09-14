package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Cpp extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor",
        "bool", "break", "case", "catch", "char", "char8_t", "char16_t", "char32_t",
        "class", "co_await", "co_return", "co_yield", "compl", "concept", "const",
        "consteval", "constexpr", "constinit", "const_cast", "continue", "decltype",
        "default", "delete", "do", "double", "dynamic_cast", "else", "enum",
        "explicit", "export", "extern", "false", "float", "for", "friend", "goto",
        "if", "inline", "int", "long", "mutable", "namespace", "new", "noexcept",
        "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private",
        "protected", "public", "register", "reinterpret_cast", "requires", "return",
        "short", "signed", "sizeof", "static", "static_assert", "static_cast",
        "struct", "switch", "template", "this", "thread_local", "throw", "true",
        "try", "typedef", "typeid", "typename", "union", "unsigned", "using",
        "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "string", "vector", "map", "set", "list", "array", "deque", "queue",
        "stack", "pair", "tuple", "unique_ptr", "shared_ptr", "weak_ptr",
        "ostream", "istream", "iostream", "ifstream", "ofstream", "fstream",
        "stringstream", "istringstream", "ostringstream", "size_t", "int8_t",
        "int16_t", "int32_t", "int64_t", "uint8_t", "uint16_t", "uint32_t",
        "uint64_t", "nullptr_t", "FILE", "exception", "runtime_error",
        "invalid_argument", "logic_error", "out_of_range", "overflow_error"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "nullptr", "EOF", "NULL"
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
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'|'\\\\u[0-9a-fA-F]+'"; }

    @Override
    protected String getAnnotationPattern() { return "#\\s*\\w+"; }
}
