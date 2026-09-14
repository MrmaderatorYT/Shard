package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Ocaml extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "and", "as", "assert", "asr", "begin", "class", "constraint",
        "do", "done", "downto", "else", "end", "exception", "external",
        "false", "for", "fun", "function", "functor", "if", "in",
        "include", "inherit", "initializer", "land", "lazy", "let",
        "lor", "lsl", "lsr", "lxor", "match", "method", "mod",
        "module", "mutable", "new", "nonrec", "object", "of", "open",
        "or", "private", "rec", "sig", "struct", "then", "to", "true",
        "try", "type", "val", "virtual", "when", "while", "with"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "int", "float", "bool", "char", "string", "unit", "list", "option",
        "Some", "None", "Ok", "Error", "array", "ref", "exn", "seq",
        "result", "Either", "Map", "Set", "Hashtbl", "Queue", "Stack",
        "Lazy", "Stream", "String", "Bytes", "List", "Array", "Seq",
        "Printf", "Scanf", "Format", "Arg", "Sys", "Unix", "Filename",
        "Printexc", "Printexc", "Marshal", "Weak", "Gc"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "None", "Some", "Ok", "Error", "()", "[]"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "(*[\\s\\S]*?)"; }

    @Override
    protected String getBlockCommentStart() { return "(*"; }

    @Override
    protected String getBlockCommentEnd() { return "*)"; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }
}
