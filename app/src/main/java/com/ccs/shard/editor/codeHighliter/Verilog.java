package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Verilog extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "always", "and", "assign", "begin", "buf", "bufif0", "bufif1",
        "case", "casex", "casez", "cmos", "deassign", "default", "defparam",
        "delay", "disable", "edge", "else", "end", "endcase", "endfunction",
        "endmodule", "endprimitive", "endspecify", "endtable", "endtask",
        "event", "for", "force", "forever", "fork", "function", "generate",
        "genvar", "highz0", "highz1", "if", "ifnone", "initial", "inout",
        "input", "integer", "join", "large", "macromodule", "medium", "module",
        "nand", "negedge", "nmos", "nor", "noshowcancelled", "not",
        "notif0", "notif1", "or", "output", "parameter", "pmos", "posedge",
        "primitive", "pull0", "pull1", "pulldown", "pullup", "pullup",
        "rcmos", "real", "realtime", "reg", "release", "repeat", "rmos",
        "rpmos", "rtran", "rtranif0", "rtranif1", "scalared", "showcancelled",
        "signed", "small", "specify", "specparam", "strong0", "strong1",
        "supply0", "supply1", "table", "task", "time", "tran", "tranif0",
        "tranif1", "tri", "tri0", "tri1", "triand", "trior", "trireg",
        "unsigned", "use", "vectored", "wait", "wand", "weak0", "weak1",
        "while", "wire", "wor", "xnor", "xor"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "wire", "reg", "integer", "real", "realtime", "time", "supply0",
        "supply1", "tri", "tri0", "tri1", "trireg", "wand", "wor",
        "input", "output", "inout", "parameter", "localparam"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "1'b0", "1'b1", "1'bx", "1'bz", "0", "1", "x", "z",
        "true", "false"
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
    protected String getStringPattern() { return "\"[^\"]*\""; }

    @Override
    protected String getCharPattern() { return "'[0-9]+'[bhBH][0-9a-fA-FxzXZ]+|'[01xz]'"; }

    @Override
    protected String getNumberPattern() { return "\\d+'[bhBH][0-9a-fA-FxzXZ]+|\\d+\\.?\\d*([eE][+-]?\\d+)?"; }

    @Override
    protected String getAnnotationPattern() { return "`[a-zA-Z_][a-zA-Z0-9_]*"; }

    @Override
    protected String getFunctionPattern() { return "\\b(module|function|task|primitive)\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
