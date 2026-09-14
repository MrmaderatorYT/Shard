package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Ruby extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "BEGIN", "END", "alias", "and", "begin", "break", "case", "class",
        "def", "defined?", "do", "else", "elsif", "end", "ensure", "false",
        "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
        "rescue", "retry", "return", "self", "super", "then", "true", "undef",
        "unless", "until", "when", "while", "yield", "raise", "lambda",
        "proc", "private", "protected", "public", "attr_reader", "attr_writer",
        "attr_accessor", "include", "extend", "prepend", "require", "require_relative",
        "autoload", "const_get", "const_set", "method_defined?", "respond_to?",
        "send", "instance_method", "class_method", "freeze", "dup", "tap",
        "then", "yield_self", "loop", "times", "each", "map", "select",
        "reject", "reduce", "inject", "any?", "all?", "none?", "one?",
        "find", "find_index", "include?", "empty?", "nil?", "zero?", "size",
        "length", "count", "min", "max", "sort", "reverse", "flatten",
        "compact", "uniq", "join", "split", "gsub", "sub", "strip",
        "chomp", "chop", "to_s", "to_i", "to_f", "to_a", "to_h", "to_sym",
        "puts", "print", "p", "pp", "gets", "STDIN", "STDOUT", "STDERR"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Integer", "Float", "String", "Array", "Hash", "Symbol", "Regexp",
        "NilClass", "TrueClass", "FalseClass", "Object", "Class", "Module",
        "Proc", "Lambda", "Range", "File", "Dir", "IO", "Socket",
        "Thread", "Mutex", "Comparable", "Enumerable", "Kernel",
        "BasicObject", "Struct", "Data"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "nil", "self", "nil?", "true", "false",
        "__FILE__", "__LINE__", "__ENCODING__", "__method__", "__callee__"
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
    protected String getBlockCommentStart() { return "=begin"; }

    @Override
    protected String getBlockCommentEnd() { return "=end"; }

    @Override
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^']*'"; }

    @Override
    protected String getAnnotationPattern() { return "@@?[a-zA-Z_][a-zA-Z0-9_]*"; }

    @Override
    protected String getFunctionPattern() { return "\\b(def|class|module)\\s+([a-zA-Z_][a-zA-Z0-9_]*(::[a-zA-Z_][a-zA-Z0-9_]*)*)"; }
}
