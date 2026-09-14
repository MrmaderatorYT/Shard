package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Csharp extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch",
        "char", "checked", "class", "const", "continue", "decimal", "default",
        "delegate", "do", "double", "else", "enum", "event", "explicit", "extern",
        "false", "finally", "fixed", "float", "for", "foreach", "goto", "if",
        "implicit", "in", "int", "interface", "internal", "is", "lock", "long",
        "namespace", "new", "null", "object", "operator", "out", "override",
        "params", "private", "protected", "public", "readonly", "ref", "return",
        "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string",
        "struct", "switch", "this", "throw", "true", "try", "typeof", "uint",
        "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void",
        "volatile", "while", "var", "async", "await", "yield", "when", "where"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Console", "String", "Int32", "Int64", "Boolean", "Double", "Float",
        "Object", "Exception", "Array", "List", "Dictionary", "HashSet",
        "Queue", "Stack", "Tuple", "Task", "Span", "Memory", "ValueTask",
        "Action", "Func", "IEnumerable", "ICollection", "IList", "IDictionary",
        "Math", "DateTime", "TimeSpan", "Guid", "Uri", "Regex", "Debug",
        "StringBuilder", "FileStream", "StreamReader", "StreamWriter"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null"
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
    protected String getStringPattern() { return "@\"[^\"]*\"|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getAnnotationPattern() { return "\\[[a-zA-Z_][a-zA-Z0-9_.]*(\\([^)]*\\))?\\]"; }
}
