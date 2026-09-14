package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Swift extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "associatedtype", "break", "case", "catch", "class", "continue",
        "default", "defer", "deinit", "do", "dynamic", "else", "enum",
        "extension", "fallthrough", "false", "fileprivate", "final", "for",
        "func", "guard", "if", "import", "in", "init", "inout", "internal",
        "is", "lazy", "let", "nil", "open", "operator", "optional", "override",
        "postfix", "prefix", "private", "protocol", "public", "repeat",
        "required", "rethrows", "return", "self", "Self", "some", "static",
        "struct", "subscript", "super", "switch", "throw", "throws", "true",
        "try", "typealias", "unowned", "var", "weak", "where", "while",
        "willSet", "didSet", "get", "set", "some", "any", "async", "await"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Array", "Bool", "Character", "ClosedRange", "Collection", "ContiguousArray",
        "CountableRange", "Date", "Dictionary", "Double", "Float", "Hashable",
        "Int", "Int8", "Int16", "Int32", "Int64", "Optional", "Range",
        "Set", "String", "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
        "Void", "Any", "AnyObject", "Codable", "Decodable", "Encodable",
        "Error", "Identifiable", "Equatable", "Comparable", "CustomStringConvertible",
        "Sequence", "IteratorProtocol", "Comparable", "Numeric"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "nil", "self", "Self", "super"
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
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }
}
