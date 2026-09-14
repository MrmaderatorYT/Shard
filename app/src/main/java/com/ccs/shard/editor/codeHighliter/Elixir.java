package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Elixir extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "after", "and", "case", "catch", "cond", "def", "defp", "defmodule",
        "defprotocol", "defstruct", "defimpl", "defmacro", "defmacrop",
        "defoverridable", "defdelegate", "defguard", "defguardp",
        "do", "else", "end", "fn", "for", "if", "import", "in",
        "not", "or", "quote", "raise", "receive", "rescue", "return",
        "try", "unless", "unquote", "unquote_splicing", "use", "when",
        "with", "alias", "__CALLER__", "__DIR__", "__ENV__", "__MODULE__",
        "__STACKTRACE__", "__FILE__"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Atom", "Binary", "Bitstring", "Float", "Function", "Integer",
        "List", "Map", "MapSet", "Port", "PID", "Reference", "Tuple",
        "Keyword", "Range", "Stream", "String", "Enum", "Collectable",
        "Enumerable", "Inspect", "ListChars", "String.Chars", "Sigil",
        "HashDict", "HashSet", "Dict", "Set", "Access"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "nil"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "#.*"; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"\"\"[\\s\\S]*?\"\"\"|~[a-zA-Z][a-zA-Z]*\\([^)]*\\)|~[a-zA-Z][a-zA-Z]*\\[[^\\]]*\\]|~[a-zA-Z][a-zA-Z]*\\{[^}]*\\}|~[a-zA-Z][a-zA-Z]*\\|[^|]*\\||\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }

    @Override
    protected String getFunctionPattern() { return "\\b(def|defp|defmodule|defprotocol|defstruct|defimpl|defmacro|defmacrop)\\s+([a-zA-Z_][a-zA-Z0-9_!?]*)"; }
}
