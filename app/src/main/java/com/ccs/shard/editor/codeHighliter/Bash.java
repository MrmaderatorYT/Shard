package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Bash extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "while",
        "until", "do", "done", "in", "function", "select", "time", "coproc",
        "break", "continue", "return", "exit", "local", "declare", "typeset",
        "export", "readonly", "unset", "shift", "source", "eval", "exec",
        "trap", "wait", "kill", "read", "echo", "printf", "test", "[",
        "[[", "]]", "let", "set", "shopt", "builtin", "command", "hash",
        "type", "which", "alias", "unalias", "bg", "fg", "jobs", "suspend",
        "nohup", "disown", "wait", "pushd", "popd", "dirs"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "string", "integer", "float", "array", "assoc", "readonly",
        "declare", "typeset", "export", "local", "global", "env"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "0", "1", "$?", "$$", "$!", "$$", "$@",
        "$*", "$#", "$-", "$1", "$2", "$3", "$4", "$5", "$6", "$7", "$8", "$9"
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
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^']*'"; }

    @Override
    protected String getAnnotationPattern() { return "\\$\\{?[a-zA-Z_][a-zA-Z0-9_]*\\}?"; }
}
