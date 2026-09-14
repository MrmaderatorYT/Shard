package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Scala extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "case", "catch", "class", "def", "do", "else", "enum",
        "export", "extends", "false", "final", "finally", "for", "forSome",
        "given", "if", "implicit", "import", "infix", "lazy", "match", "new",
        "null", "object", "old", "override", "package", "private", "protected",
        "return", "sealed", "self", "super", "this", "throw", "trait", "true",
        "try", "type", "var", "val", "while", "with", "yield", "using",
        "erased", "inline", "opaque", "transparent", "end", "extension",
        "derives", "enum", "then", "catch", "do", "while", "for", "match",
        "if", "else", "yield", "throw", "return", "try", "catch", "finally",
        "for", "do", "while", "match", "case", "if", "else", "yield"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Any", "AnyVal", "AnyRef", "Nothing", "Null", "Unit", "Boolean",
        "Byte", "Short", "Char", "Int", "Long", "Float", "Double",
        "String", "Option", "Some", "None", "List", "Map", "Set", "Seq",
        "Vector", "Array", "Tuple", "Either", "Left", "Right", "Future",
        "Promise", "Try", "Success", "Failure", "Nil", "Cons",
        "Tuple1", "Tuple2", "Tuple3", "Tuple4", "Tuple5"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null", "this", "super"
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
    protected String getStringPattern() { return "\"\"\"[\\s\\S]*?\"\"\"|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }

    @Override
    protected String getFunctionPattern() { return "\\b(def|class|object|trait|enum|given)\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
