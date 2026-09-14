package com.ccs.shard.editor.codeHighliter;

import android.content.Context;

import java.util.Locale;


/**
 * Centralized language detection and Highlighter factory.
 * Maps language tags from fenced code blocks to Highlighter implementations.
 * Falls back to heuristic content analysis when no tag is provided.
 */
public class LanguageDetector {

    /**
     * Get a Highlighter for the given language tag (e.g., "python", "java", "js").
     * Returns null if the language is not recognized.
     */
    public static Highlighter forLanguage(String lang) {
        if (lang == null || lang.isEmpty()) return null;
        lang = normalizeLanguageTag(lang);
        switch (lang) {
            case "java": return new Java();
            case "javascript":
            case "js": return new JavaScript();
            case "typescript":
            case "ts": return new TypeScript();
            case "kotlin":
            case "kt": return new Kotlin();
            case "python":
            case "py": return new Python();
            case "c": return new C();
            case "cpp":
            case "c++":
            case "cc":
            case "h":
            case "hpp": return new Cpp();
            case "csharp":
            case "c#":
            case "cs": return new Csharp();
            case "go":
            case "golang": return new Go();
            case "rust":
            case "rs": return new Rust();
            case "swift": return new Swift();
            case "dart": return new Dart();
            case "bash":
            case "sh":
            case "shell":
            case "zsh": return new Bash();
            case "ruby":
            case "rb": return new Ruby();
            case "php": return new Php();
            case "sql": return new Sql();
            case "html": return new Html();
            case "css": return new Css();
            case "xml": return new Xml();
            case "json": return new Json();
            case "yaml":
            case "yml": return new Yaml();
            case "markdown":
            case "md": return new Markdown();
            case "tex":
            case "latex":
            case "ltx": return new Tex();
            case "scala": return new Scala();
            case "perl":
            case "pl": return new Perl();
            case "lua": return new Lua();
            case "r": return new R();
            case "haskell":
            case "hs": return new Haskell();
            case "elixir":
            case "ex":
            case "exs": return new Elixir();
            case "erlang":
            case "erl": return new Erlang();
            case "ocaml":
            case "ml": return new Ocaml();
            case "groovy": return new Groovy();
            case "nix": return new Nix();
            case "fortran":
            case "f90":
            case "f95":
            case "f03": return new Fortran();
            case "objectivec":
            case "objective-c":
            case "objc":
            case "m": return new ObjectiveC();
            case "vbnet":
            case "vb":
            case "vb.net": return new VbNet();
            case "verilog":
            case "v":
            case "sv": return new Verilog();
            case "assembly":
            case "asm":
            case "nasm": return new Assembly();
            case "zig": return new Zig();
            default: return null;
        }
    }

    private static String normalizeLanguageTag(String lang) {
        lang = lang.toLowerCase(Locale.ROOT).trim();
        int space = lang.indexOf(' ');
        if (space >= 0) {
            lang = lang.substring(0, space);
        }
        if (lang.startsWith("language-")) {
            lang = lang.substring("language-".length());
        }
        if (lang.startsWith(".")) {
            lang = lang.substring(1);
        }
        return lang;
    }

    /**
     * Get a Highlighter for the given language tag, with theme applied from settings.
     * Returns null if the language is not recognized.
     */
    public static Highlighter forLanguage(String lang, Context context) {
        // The theme is the caller's decision: a code block in the editor follows
        // the app theme, while the PDF exporter always needs the light one.
        return forLanguage(lang);
    }

    /**
     * Detect language from code content using heuristic analysis.
     * Used as a fallback when no language tag is specified.
     */
    public static String detectFromContent(String code) {
        if (code == null || code.isEmpty()) return null;
        String trimmed = code.trim();

        if (trimmed.contains("public class ") || trimmed.contains("private static ") ||
            trimmed.contains("System.out.") || trimmed.contains("import java.")) return "java";
        if (trimmed.contains("function ") || trimmed.contains("const ") ||
            trimmed.contains("=> ") || trimmed.contains("console.log")) return "javascript";
        if (trimmed.contains("fun ") || trimmed.contains("val ") ||
            trimmed.contains("data class ") || trimmed.contains("suspend fun")) return "kotlin";
        if (trimmed.contains("def ") || (trimmed.contains("import ") && trimmed.contains("from ")) ||
            trimmed.contains("print(") || trimmed.contains("__init__")) return "python";
        if (trimmed.contains("#include") || trimmed.contains("int main(") ||
            trimmed.contains("std::")) return "cpp";
        if (trimmed.contains("func ") || trimmed.contains("package main") ||
            trimmed.contains("fmt.Println")) return "go";
        if (trimmed.contains("fn ") || trimmed.contains("let mut ") ||
            trimmed.contains("impl ") || trimmed.contains("pub fn")) return "rust";
        if (trimmed.contains("#!/bin/bash") || trimmed.contains("echo ") ||
            trimmed.contains("$(") || trimmed.contains("fi\n")) return "bash";
        if (trimmed.contains("SELECT ") || trimmed.contains("INSERT ") ||
            trimmed.contains("CREATE TABLE")) return "sql";
        if (trimmed.contains("<!DOCTYPE") || trimmed.contains("<html")) return "html";
        if (trimmed.contains("@interface") || trimmed.contains("#import")) return "objectivec";
        if (trimmed.contains("struct ") && trimmed.contains("var ") ||
            trimmed.contains("@objc") || trimmed.contains("guard let")) return "swift";
        if (trimmed.contains("\\documentclass") || trimmed.contains("\\begin{document}")
                || trimmed.contains("\\usepackage{") || trimmed.contains("\\begin{equation}")) {
            return "tex";
        }

        return null;
    }

    /**
     * Get a themed Highlighter by trying language tag first, then heuristic detection.
     */
    public static Highlighter resolve(String langTag, String code, Context context) {
        // Try language tag first
        Highlighter h = forLanguage(langTag, context);
        if (h != null) return h;

        // Fall back to heuristic
        String detected = detectFromContent(code);
        return forLanguage(detected, context);
    }
}
