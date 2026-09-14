package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class TypeScript extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "enum", "export", "extends",
        "finally", "for", "function", "if", "implements", "import", "in",
        "instanceof", "interface", "let", "new", "of", "package", "private",
        "protected", "public", "return", "static", "super", "switch", "this",
        "throw", "try", "type", "typeof", "var", "void", "while", "with",
        "yield", "async", "await", "from", "as", "abstract", "declare",
        "readonly", "override", "keyof", "infer", "extends", "satisfies",
        "namespace", "module", "is", "asserts", "never", "unknown", "any",
        "object", "boolean", "string", "number", "bigint", "symbol", "undefined",
        "null", "true", "false"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Array", "Boolean", "Date", "Error", "Function", "JSON", "Map", "Math",
        "Number", "Object", "Promise", "Proxy", "Readonly", "Partial", "Required",
        "Record", "Pick", "Omit", "Exclude", "Extract", "NonNullable",
        "Parameters", "ReturnType", "ConstructorParameters", "InstanceType"
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
