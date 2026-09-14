package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Php extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "and", "array", "as", "break", "callable", "case", "catch",
        "class", "clone", "const", "continue", "declare", "default", "die",
        "do", "echo", "else", "elseif", "empty", "enddeclare", "endfor",
        "endforeach", "endif", "endswitch", "endwhile", "eval", "exit",
        "extends", "final", "finally", "fn", "for", "foreach", "function",
        "global", "goto", "if", "implements", "include", "include_once",
        "instanceof", "insteadof", "interface", "isset", "list", "match",
        "namespace", "new", "or", "print", "private", "protected", "public",
        "readonly", "require", "require_once", "return", "static", "switch",
        "throw", "trait", "try", "unset", "use", "var", "while", "xor",
        "yield", "yield from"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "int", "float", "string", "bool", "array", "object", "callable",
        "iterable", "void", "never", "null", "self", "static", "parent",
        "mixed", "true", "false", "true", "false"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null", "PHP_INT_MAX", "PHP_FLOAT_MAX",
        "__LINE__", "__FILE__", "__DIR__", "__FUNCTION__", "__CLASS__",
        "__TRAIT__", "__METHOD__", "__NAMESPACE__", "__halt_compiler"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "//.*|#.*"; }

    @Override
    protected String getBlockCommentStart() { return "/*"; }

    @Override
    protected String getBlockCommentEnd() { return "*/"; }

    @Override
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^']*'"; }

    @Override
    protected String getAnnotationPattern() { return "#\\[\\w+\\]"; }

    @Override
    protected String getFunctionPattern() { return "\\bfunction\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
