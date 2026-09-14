package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Assembly extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "mov", "push", "pop", "add", "sub", "mul", "div", "inc", "dec",
        "and", "or", "xor", "not", "shl", "shr", "rol", "ror",
        "cmp", "test", "jmp", "je", "jne", "jg", "jge", "jl", "jle",
        "ja", "jae", "jb", "jbe", "call", "ret", "int", "nop", "hlt",
        "syscall", "sysenter", "sysexit", "iret", "cli", "sti", "cld", "std",
        "enter", "leave", "loop", "loope", "loopne", "rep", "repe", "repne",
        "lods", "stos", "movs", "cmps", "scas", "out", "in", "lgdt", "lidt",
        "ltr", "lldt", "sgdt", "sidt", "sldt", "str", "pusha", "popa",
        "pushad", "popad", "pushf", "popf", "lahf", "sahf", "cbw", "cwd",
        "cdq", "cqo", "cwde", "movsx", "movzx", "movsxd", "xchg",
        "bswap", "cmpxchg", "xadd", "lock", "imul", "idiv", "cbw", "cwd",
        "cdq", "cqo", "cwde", "movsx", "movzx", "movsxd", "xchg",
        "bswap", "cmpxchg", "xadd", "lock", "imul", "idiv"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "eax", "ebx", "ecx", "edx", "esi", "edi", "esp", "ebp",
        "rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rsp", "rbp",
        "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15",
        "al", "ah", "bl", "bh", "cl", "ch", "dl", "dh",
        "ax", "bx", "cx", "dx", "si", "di", "sp", "bp",
        "cs", "ds", "ss", "es", "fs", "gs", "cr0", "cr2", "cr3", "cr4",
        "xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7",
        "ymm0", "ymm1", "ymm2", "ymm3", "ymm4", "ymm5", "ymm6", "ymm7"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "byte", "word", "dword", "qword", "tbyte", "ptr", "near", "far",
        "section", "global", "extern", "default", "align", "resb", "resw",
        "resd", "resq", "db", "dw", "dd", "dq", "dt"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return ";.*|#.*"; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\"|'[^']*'"; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "\\.[a-zA-Z_][a-zA-Z0-9_]*"; }
}
