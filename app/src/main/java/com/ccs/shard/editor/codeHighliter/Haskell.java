package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Haskell extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "as", "case", "class", "data", "default", "deriving", "do", "else",
        "family", "forall", "hiding", "if", "import", "in", "infix", "infixl",
        "infixr", "instance", "let", "mdo", "module", "newtype", "of", "open",
        "qualified", "rec", "then", "type", "where"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Bool", "Char", "Double", "Either", "Float", "IO", "Int", "Integer",
        "Just", "Left", "Maybe", "Nothing", "Ordering", "Read", "Right",
        "Show", "String", "Text", "Vector", "Map", "Set", "List", "Tuple",
        "Maybe", "Either", "IO", "Reader", "Writer", "State", "Cont",
        "Monad", "Functor", "Applicative", "Foldable", "Traversable"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "True", "False", "Nothing", "Just", "Left", "Right", "LT", "EQ", "GT",
        "undefined", "error", "map", "filter", "foldr", "foldl", "head",
        "tail", "length", "reverse", "zip", "zipWith", "take", "drop",
        "splitAt", "elem", "notElem", "null", "repeat", "replicate",
        "cycle", "iterate", "unfoldr", "scanl", "scanr", "iterate"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "--.*"; }

    @Override
    protected String getBlockCommentStart() { return "{-"; }

    @Override
    protected String getBlockCommentEnd() { return "-}"; }

    @Override
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getFunctionPattern() { return "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=::)"; }
}
