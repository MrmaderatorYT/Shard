package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Fortran extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "allocatable", "allocate", "assign", "associated", "backspace",
        "block", "call", "case", "character", "close", "common", "complex",
        "contains", "continue", "cycle", "data", "deallocate", "default",
        "dimension", "do", "elemental", "else", "elseif", "end", "enddo",
        "endif", "endfile", "endfunction", "endinterface", "endmodule",
        "endprogram", "endselect", "endsubroutine", "endtype", "entry",
        "equivalence", "exit", "external", "format", "function", "goto",
        "if", "implicit", "in", "inout", "include", "interface", "intrinsic",
        "intent", "inquire", "integer", "kind", "logical", "loop", "module",
        "namelist", "none", "nopass", "nullify", "only", "open", "operator",
        "optional", "out", "parameter", "pause", "pointer", "print",
        "private", "procedure", "program", "protected", "public", "pure",
        "read", "real", "recursive", "result", "return", "rewind", "save",
        "select", "sequence", "statement", "static", "stop", "subroutine",
        "target", "then", "type", "use", "value", "volatile", "where",
        "while", "write", "abstract", "non_overridable", "nopass",
        "pass", "deferred", "non_recursive", "final", "generic",
        "overridable", "recurrrent", "protected"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "integer", "real", "complex", "logical", "character", "type",
        "class", "procedure", "function", "subroutine", "module",
        "program", "block", "interface", "enum", "namelist"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", ".true.", ".false.", ".and.", ".or.", ".not.",
        ".eq.", ".ne.", ".lt.", ".gt.", ".le.", ".ge.", ".eqv.", ".neqv."
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "!.*"; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getFunctionPattern() { return "\\b(function|subroutine)\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
