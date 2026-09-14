package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Groovy extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "as", "assert", "break", "case", "catch", "class",
        "const", "continue", "def", "default", "do", "else", "enum",
        "extends", "final", "finally", "for", "goto", "if", "implements",
        "import", "in", "instanceof", "interface", "new", "package", "return",
        "static", "super", "switch", "synchronized", "this", "throw", "throws",
        "trait", "try", "var", "void", "volatile", "while"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "String", "Integer", "Double", "Float", "Long", "Short", "Byte",
        "Boolean", "Character", "Object", "List", "Map", "Set", "ArrayList",
        "HashMap", "HashSet", "LinkedList", "TreeMap", "TreeSet", "LinkedHashMap",
        "BigDecimal", "BigInteger", "File", "FileReader", "FileWriter",
        "BufferedReader", "BufferedWriter", "InputStream", "OutputStream",
        "FileInputStream", "FileOutputStream"
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
    protected String getStringPattern() { return "\"\"\"[\\s\\S]*?\"\"\"|/[^/\\n]+/|'[^'\\\\]*(\\\\.[^'\\\\]*)*'"; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }
}
