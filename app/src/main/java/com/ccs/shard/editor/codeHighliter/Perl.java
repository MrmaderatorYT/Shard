package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Perl extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "if", "elsif", "else", "unless", "given", "when", "default",
        "while", "until", "for", "foreach", "do", "continue", "break",
        "sub", "return", "my", "our", "local", "state", "package",
        "use", "no", "require", "BEGIN", "END", "INIT", "CHECK", "UNITCHECK",
        "die", "warn", "exit", "eval", "redo", "last", "next", "goto",
        "not", "and", "or", "cmp", "eq", "ne", "lt", "gt", "le", "ge",
        "isa", "ref", "bless", "undef", "defined", "delete", "exists",
        "keys", "values", "each", "push", "pop", "shift", "unshift",
        "splice", "split", "join", "map", "grep", "sort", "reverse",
        "print", "say", "printf", "sprintf", "open", "close", "read",
        "write", "seek", "tell", "eof", "binmode", "fileno", "select",
        "pipe", "system", "exec", "fork", "wait", "waitpid", "kill",
        "socket", "connect", "bind", "listen", "accept", "send", "recv",
        "select", "vec", "pack", "unpack", "study", "substr", "index",
        "rindex", "chomp", "chop", "chr", "ord", "crypt", "uc", "lc",
        "ucfirst", "lcfirst", "quotemeta", "hex", "oct", "length", "pos",
        "sprintf", "formline", "time", "localtime", "gmtime", "times",
        "alarm", "sleep", "getlogin", "gethostbyname", "gethostbyaddr",
        "getprotobyname", "getprotoent", "getservbyname", "getservbyport",
        "getservent", "getpwent", "getpwnam", "getpwuid", "getgrnam",
        "getgrgid", "getnetbyname", "getnetent", "getsockname", "getpeername",
        "syscall", "chdir", "chroot", "glob", "readlink", "link", "unlink",
        "mkdir", "rmdir", "rename", "stat", "lstat", "fileno", "truncate",
        "fcntl", "ioctl", "flock", "utime", "chmod", "chown", "umask",
        "dump", "fork", "dump", "exec", "exit", "warn", "die", "eval"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Scalar", "Array", "Hash", "Code", "Regexp", "Glob", "File",
        "IO::Socket", "IO::Handle", "File::Spec", "File::Path", "File::Find",
        "File::Basename", "File::Copy", "File::stat", "Cwd", "Fcntl",
        "POSIX", "Socket", "Errno", " Carp", "warnings", "strict", "utf8",
        "overload", "constant", "vars", "autodie", "Try::Tiny", "Moo",
        "Moose", "Dancer", "Mojolicious", "DBI", "DBD::SQLite"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "undef", "__FILE__", "__LINE__", "__PACKAGE__",
        "__SUB__", "__END__", "__DATA__"
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
    protected String getAnnotationPattern() { return "\\$[a-zA-Z_][a-zA-Z0-9_]*|@[a-zA-Z_][a-zA-Z0-9_]*|%[a-zA-Z_][a-zA-Z0-9_]*"; }
}
