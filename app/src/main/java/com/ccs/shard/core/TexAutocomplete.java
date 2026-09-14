package com.ccs.shard.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure TeX command matching and snippet expansion used by the raw editor. */
public final class TexAutocomplete {

    private TexAutocomplete() {}

    private static final char CURSOR = '\u0000';

    private static final Command[] ENVIRONMENTS = {
            command("document", "\\begin{document}\n" + CURSOR + "\n\\end{document}"),
            command("titlepage", "\\begin{titlepage}\n" + CURSOR + "\n\\end{titlepage}"),
            command("minipage", "\\begin{minipage}{" + CURSOR + "\\textwidth}\n\n\\end{minipage}"),
            command("itemize", "\\begin{itemize}\n  \\item " + CURSOR + "\n\\end{itemize}"),
            command("enumerate", "\\begin{enumerate}\n  \\item " + CURSOR + "\n\\end{enumerate}"),
            command("equation", "\\begin{equation}\n  " + CURSOR + "\n\\end{equation}"),
            command("align", "\\begin{align}\n  " + CURSOR + "\n\\end{align}"),
            command("gather", "\\begin{gather}\n  " + CURSOR + "\n\\end{gather}"),
            command("multline", "\\begin{multline}\n  " + CURSOR + "\n\\end{multline}"),
            command("table", "\\begin{table}\n  \\centering\n  " + CURSOR + "\n\\end{table}"),
            command("longtable", "\\begin{longtable}{" + CURSOR + "}\n  \\caption{}\\\\ \n  \\hline\n  \n\\end{longtable}"),
            command("landscape", "\\begin{landscape}\n" + CURSOR + "\n\\end{landscape}"),
            command("figure", "\\begin{figure}\n  \\centering\n  " + CURSOR + "\n\\end{figure}"),
            command("tikzpicture", "\\begin{tikzpicture}\n  " + CURSOR + "\n\\end{tikzpicture}"),
            command("tabular", "\\begin{tabular}{" + CURSOR + "}\n  \n\\end{tabular}"),
            command("matrix", "\\begin{matrix}\n  " + CURSOR + "\n\\end{matrix}"),
            command("pmatrix", "\\begin{pmatrix}\n  " + CURSOR + "\n\\end{pmatrix}"),
            command("bmatrix", "\\begin{bmatrix}\n  " + CURSOR + "\n\\end{bmatrix}"),
            command("vmatrix", "\\begin{vmatrix}\n  " + CURSOR + "\n\\end{vmatrix}"),
            command("cases", "\\begin{cases}\n  " + CURSOR + "\n\\end{cases}"),
            command("center", "\\begin{center}\n  " + CURSOR + "\n\\end{center}"),
            command("flushright", "\\begin{flushright}\n  " + CURSOR + "\n\\end{flushright}"),
            command("flushleft", "\\begin{flushleft}\n  " + CURSOR + "\n\\end{flushleft}"),
            command("thebibliography", "\\begin{thebibliography}{99}\n  \\bibitem{" + CURSOR + "}\n\\end{thebibliography}"),
            command("quote", "\\begin{quote}\n  " + CURSOR + "\n\\end{quote}"),
            command("quotation", "\\begin{quotation}\n  " + CURSOR + "\n\\end{quotation}"),
            command("verbatim", "\\begin{verbatim}\n" + CURSOR + "\n\\end{verbatim}"),
            command("abstract", "\\begin{abstract}\n" + CURSOR + "\n\\end{abstract}")
    };

    private static final Command[] COMMANDS = {
            command("documentclass", "\\documentclass[" + CURSOR + "]{article}"),
            command("usepackage", "\\usepackage{" + CURSOR + "}"),
            command("begin", "\\begin{" + CURSOR),
            command("end", "\\end{" + CURSOR + "}"),
            command("begin", "\\begin{document}\n" + CURSOR + "\n\\end{document}"),
            command("begin", "\\begin{titlepage}\n" + CURSOR + "\n\\end{titlepage}"),
            command("begin", "\\begin{minipage}{" + CURSOR + "\\textwidth}\n\n\\end{minipage}"),
            command("begin", "\\begin{itemize}\n  \\item " + CURSOR + "\n\\end{itemize}"),
            command("begin", "\\begin{enumerate}\n  \\item " + CURSOR + "\n\\end{enumerate}"),
            command("begin", "\\begin{equation}\n  " + CURSOR + "\n\\end{equation}"),
            command("begin", "\\begin{align}\n  " + CURSOR + "\n\\end{align}"),
            command("begin", "\\begin{table}\n  \\centering\n  " + CURSOR + "\n\\end{table}"),
            command("begin", "\\begin{longtable}{" + CURSOR + "}\n  \\caption{}\\\\ \n  \\hline\n  \n\\end{longtable}"),
            command("begin", "\\begin{landscape}\n" + CURSOR + "\n\\end{landscape}"),
            command("begin", "\\begin{figure}\n  \\centering\n  " + CURSOR + "\n\\end{figure}"),
            command("begin", "\\begin{tikzpicture}\n  " + CURSOR + "\n\\end{tikzpicture}"),
            command("begin", "\\begin{tabular}{" + CURSOR + "}\n  \n\\end{tabular}"),
            command("begin", "\\begin{matrix}\n  " + CURSOR + "\n\\end{matrix}"),
            command("begin", "\\begin{pmatrix}\n  " + CURSOR + "\n\\end{pmatrix}"),
            command("begin", "\\begin{bmatrix}\n  " + CURSOR + "\n\\end{bmatrix}"),
            command("begin", "\\begin{cases}\n  " + CURSOR + "\n\\end{cases}"),
            command("begin", "\\begin{center}\n  " + CURSOR + "\n\\end{center}"),
            command("begin", "\\begin{flushright}\n  " + CURSOR + "\n\\end{flushright}"),
            command("begin", "\\begin{flushleft}\n  " + CURSOR + "\n\\end{flushleft}"),
            command("begin", "\\begin{thebibliography}{99}\n  \\bibitem{" + CURSOR + "}\n\\end{thebibliography}"),
            command("begin", "\\begin{verbatim}\n" + CURSOR + "\n\\end{verbatim}"),
            command("begin", "\\begin{abstract}\n" + CURSOR + "\n\\end{abstract}"),
            command("title", "\\title{" + CURSOR + "}"),
            command("author", "\\author{" + CURSOR + "}"),
            command("date", "\\date{" + CURSOR + "}"),
            command("maketitle", "\\maketitle" + CURSOR),
            command("tableofcontents", "\\tableofcontents" + CURSOR),
            command("listoffigures", "\\listoffigures" + CURSOR),
            command("listoftables", "\\listoftables" + CURSOR),
            command("section", "\\section{" + CURSOR + "}"),
            command("section*", "\\section*{" + CURSOR + "}"),
            command("structsection", "\\structsection{" + CURSOR + "}"),
            command("subsection", "\\subsection{" + CURSOR + "}"),
            command("subsubsection", "\\subsubsection{" + CURSOR + "}"),
            command("paragraph", "\\paragraph{" + CURSOR + "}"),
            command("appendix", "\\appendix" + CURSOR),
            command("textbf", "\\textbf{" + CURSOR + "}"),
            command("textit", "\\textit{" + CURSOR + "}"),
            command("texttt", "\\texttt{" + CURSOR + "}"),
            command("textsc", "\\textsc{" + CURSOR + "}"),
            command("emph", "\\emph{" + CURSOR + "}"),
            command("underline", "\\underline{" + CURSOR + "}"),
            command("term", "\\term{" + CURSOR + "}"),
            command("eng", "\\eng{" + CURSOR + "}"),
            command("MakeUppercase", "\\MakeUppercase{" + CURSOR + "}"),
            command("MakeLowercase", "\\MakeLowercase{" + CURSOR + "}"),
            command("selectlanguage", "\\selectlanguage{" + CURSOR + "}"),
            command("footnote", "\\footnote{" + CURSOR + "}"),
            command("text", "\\text{" + CURSOR + "}"),
            command("color", "\\color{" + CURSOR + "}"),
            command("textcolor", "\\textcolor{" + CURSOR + "}{}"),
            command("href", "\\href{" + CURSOR + "}{}"),
            command("url", "\\url{" + CURSOR + "}"),
            command("label", "\\label{" + CURSOR + "}"),
            command("ref", "\\ref{" + CURSOR + "}"),
            command("pageref", "\\pageref{" + CURSOR + "}"),
            command("cite", "\\cite{" + CURSOR + "}"),
            command("bibitem", "\\bibitem{" + CURSOR + "}"),
            command("includegraphics", "\\includegraphics[" + CURSOR + "]{}"),
            command("caption", "\\caption{" + CURSOR + "}"),
            command("centering", "\\centering" + CURSOR),
            command("raggedright", "\\raggedright" + CURSOR),
            command("raggedleft", "\\raggedleft" + CURSOR),
            command("hline", "\\hline" + CURSOR),
            command("toprule", "\\toprule" + CURSOR),
            command("midrule", "\\midrule" + CURSOR),
            command("bottomrule", "\\bottomrule" + CURSOR),
            command("multicolumn", "\\multicolumn{" + CURSOR + "}{c}{}"),
            command("input", "\\input{" + CURSOR + "}"),
            command("include", "\\include{" + CURSOR + "}"),
            command("bibliography", "\\bibliography{" + CURSOR + "}"),
            command("bibliographystyle", "\\bibliographystyle{" + CURSOR + "}"),
            command("item", "\\item " + CURSOR),
            command("vspace", "\\vspace{" + CURSOR + "}"),
            command("hspace", "\\hspace{" + CURSOR + "}"),
            command("vfill", "\\vfill" + CURSOR),
            command("hfill", "\\hfill" + CURSOR),
            command("clearpage", "\\clearpage" + CURSOR),
            command("newpage", "\\newpage" + CURSOR),
            command("phantomsection", "\\phantomsection" + CURSOR),
            command("addcontentsline", "\\addcontentsline{toc}{section}{" + CURSOR + "}"),
            command("captionsetup", "\\captionsetup[" + CURSOR + "]{}"),
            command("titlespacing", "\\titlespacing*{" + CURSOR + "}{0pt}{0pt}{0pt}"),
            command("titleformat", "\\titleformat{\\" + CURSOR + "}"),
            command("numberwithin", "\\numberwithin{" + CURSOR + "}{section}"),
            command("hypersetup", "\\hypersetup{" + CURSOR + "}"),
            command("pagestyle", "\\pagestyle{" + CURSOR + "}"),
            command("thispagestyle", "\\thispagestyle{" + CURSOR + "}"),
            command("fancypagestyle", "\\fancypagestyle{" + CURSOR + "}{}"),
            command("fancyhf", "\\fancyhf{}" + CURSOR),
            command("fancyhead", "\\fancyhead[" + CURSOR + "]{}"),
            command("fancyfoot", "\\fancyfoot[" + CURSOR + "]{}"),
            command("setlist", "\\setlist[" + CURSOR + "]{}"),
            command("setlength", "\\setlength{\\" + CURSOR + "}{}"),
            command("setcounter", "\\setcounter{" + CURSOR + "}{}"),
            command("onehalfspacing", "\\onehalfspacing" + CURSOR),
            command("doublespacing", "\\doublespacing" + CURSOR),
            command("singlespacing", "\\singlespacing" + CURSOR),
            command("usetikzlibrary", "\\usetikzlibrary{" + CURSOR + "}"),
            command("node", "\\node[" + CURSOR + "] {};"),
            command("draw", "\\draw[" + CURSOR + "] ;"),
            command("frac", "\\frac{" + CURSOR + "}{}"),
            command("binom", "\\binom{" + CURSOR + "}{}"),
            command("sqrt", "\\sqrt{" + CURSOR + "}"),
            command("sum", "\\sum_{" + CURSOR + "}^{}"),
            command("prod", "\\prod_{" + CURSOR + "}^{}"),
            command("int", "\\int_{" + CURSOR + "}^{}"),
            command("iint", "\\iint_{" + CURSOR + "}"),
            command("iiint", "\\iiint_{" + CURSOR + "}"),
            command("oint", "\\oint_{" + CURSOR + "}"),
            command("lim", "\\lim_{" + CURSOR + "}"),
            command("max", "\\max_{" + CURSOR + "}"),
            command("min", "\\min_{" + CURSOR + "}"),
            command("sup", "\\sup_{" + CURSOR + "}"),
            command("inf", "\\inf_{" + CURSOR + "}"),
            command("left", "\\left(" + CURSOR + "\\right)"),
            command("mathbf", "\\mathbf{" + CURSOR + "}"),
            command("mathrm", "\\mathrm{" + CURSOR + "}"),
            command("mathit", "\\mathit{" + CURSOR + "}"),
            command("mathcal", "\\mathcal{" + CURSOR + "}"),
            command("mathbb", "\\mathbb{" + CURSOR + "}"),
            command("overline", "\\overline{" + CURSOR + "}"),
            command("operatorname", "\\operatorname{" + CURSOR + "}"),
            command("newcommand", "\\newcommand{\\" + CURSOR + "}{}"),
            command("renewcommand", "\\renewcommand{\\" + CURSOR + "}{}"),
            command("tiny", "\\tiny" + CURSOR),
            command("scriptsize", "\\scriptsize" + CURSOR),
            command("footnotesize", "\\footnotesize" + CURSOR),
            command("small", "\\small" + CURSOR),
            command("normalsize", "\\normalsize" + CURSOR),
            command("large", "\\large" + CURSOR),
            command("Large", "\\Large" + CURSOR),
            command("LARGE", "\\LARGE" + CURSOR),
            command("huge", "\\huge" + CURSOR),
            command("Huge", "\\Huge" + CURSOR),
            command("alpha", "\\alpha" + CURSOR),
            command("beta", "\\beta" + CURSOR),
            command("gamma", "\\gamma" + CURSOR),
            command("delta", "\\delta" + CURSOR),
            command("epsilon", "\\epsilon" + CURSOR),
            command("theta", "\\theta" + CURSOR),
            command("lambda", "\\lambda" + CURSOR),
            command("mu", "\\mu" + CURSOR),
            command("pi", "\\pi" + CURSOR),
            command("rho", "\\rho" + CURSOR),
            command("sigma", "\\sigma" + CURSOR),
            command("phi", "\\phi" + CURSOR),
            command("psi", "\\psi" + CURSOR),
            command("omega", "\\omega" + CURSOR),
            command("Gamma", "\\Gamma" + CURSOR),
            command("Delta", "\\Delta" + CURSOR),
            command("Theta", "\\Theta" + CURSOR),
            command("Lambda", "\\Lambda" + CURSOR),
            command("Xi", "\\Xi" + CURSOR),
            command("Pi", "\\Pi" + CURSOR),
            command("Sigma", "\\Sigma" + CURSOR),
            command("Upsilon", "\\Upsilon" + CURSOR),
            command("Phi", "\\Phi" + CURSOR),
            command("Psi", "\\Psi" + CURSOR),
            command("Omega", "\\Omega" + CURSOR),
            command("partial", "\\partial" + CURSOR),
            command("nabla", "\\nabla" + CURSOR),
            command("forall", "\\forall" + CURSOR),
            command("exists", "\\exists" + CURSOR),
            command("in", "\\in" + CURSOR),
            command("notin", "\\notin" + CURSOR),
            command("subset", "\\subset" + CURSOR),
            command("subseteq", "\\subseteq" + CURSOR),
            command("supset", "\\supset" + CURSOR),
            command("supseteq", "\\supseteq" + CURSOR),
            command("cup", "\\cup" + CURSOR),
            command("cap", "\\cap" + CURSOR),
            command("to", "\\to" + CURSOR),
            command("rightarrow", "\\rightarrow" + CURSOR),
            command("leftarrow", "\\leftarrow" + CURSOR),
            command("leftrightarrow", "\\leftrightarrow" + CURSOR),
            command("Rightarrow", "\\Rightarrow" + CURSOR),
            command("Leftarrow", "\\Leftarrow" + CURSOR),
            command("Leftrightarrow", "\\Leftrightarrow" + CURSOR),
            command("equiv", "\\equiv" + CURSOR),
            command("pm", "\\pm" + CURSOR),
            command("mp", "\\mp" + CURSOR),
            command("dots", "\\dots" + CURSOR),
            command("ldots", "\\ldots" + CURSOR),
            command("cdots", "\\cdots" + CURSOR),
            command("infty", "\\infty" + CURSOR),
            command("times", "\\times" + CURSOR),
            command("cdot", "\\cdot" + CURSOR),
            command("leq", "\\leq" + CURSOR),
            command("geq", "\\geq" + CURSOR),
            command("neq", "\\neq" + CURSOR),
            command("approx", "\\approx" + CURSOR),
            command("today", "\\today" + CURSOR)
    };

    /** Matches a command prefix immediately before {@code cursor}. */
    public static List<Match> suggest(CharSequence text, int cursor, int limit) {
        if (text == null || cursor < 0 || cursor > text.length() || limit <= 0) {
            return Collections.emptyList();
        }
        List<Match> environments = suggestEnvironment(text, cursor, limit);
        if (environments != null) return environments;
        int slash = cursor - 1;
        while (slash >= 0) {
            char c = text.charAt(slash);
            if (Character.isLetter(c) || c == '*') slash--;
            else break;
        }
        if (slash < 0 || text.charAt(slash) != '\\') return Collections.emptyList();
        // A second backslash is a TeX line break, not the start of a command.
        if (slash > 0 && text.charAt(slash - 1) == '\\') return Collections.emptyList();

        String query = text.subSequence(slash + 1, cursor)
                .toString().toLowerCase(Locale.ROOT);
        List<Match> out = new ArrayList<>(Math.min(limit, 8));
        for (Command command : COMMANDS) {
            if (!command.key.startsWith(query)) continue;
            out.add(new Match(command.label, command.insertion,
                    slash, command.caretInInsertion));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Returns null when the caret is not inside a \begin{... prefix. */
    private static List<Match> suggestEnvironment(CharSequence text, int cursor, int limit) {
        int nameStart = cursor;
        while (nameStart > 0 && validEnvironmentChar(text.charAt(nameStart - 1))) nameStart--;
        int replaceStart = nameStart - "\\begin{".length();
        if (replaceStart < 0 || !regionMatches(text, replaceStart, "\\begin{")) return null;
        String query = text.subSequence(nameStart, cursor).toString().toLowerCase(Locale.ROOT);
        List<Match> out = new ArrayList<>(Math.min(limit, 8));
        for (Command environment : ENVIRONMENTS) {
            if (!environment.key.startsWith(query)) continue;
            out.add(new Match(environment.label, environment.insertion,
                    replaceStart, environment.caretInInsertion));
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static boolean regionMatches(CharSequence text, int start, String expected) {
        if (start < 0 || start + expected.length() > text.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (text.charAt(start + i) != expected.charAt(i)) return false;
        }
        return true;
    }

    /** Builds the closing half for an arbitrary freshly typed \begin{...}. */
    public static EnvironmentExpansion environmentExpansion(CharSequence text, int cursor) {
        if (text == null || cursor < 8 || cursor > text.length()
                || text.charAt(cursor - 1) != '}') return null;
        int searchStart = Math.max(0, cursor - 120);
        String window = text.subSequence(searchStart, cursor).toString();
        int beginInWindow = window.lastIndexOf("\\begin{");
        if (beginInWindow < 0 || beginInWindow + 7 >= window.length() - 1) return null;
        int begin = searchStart + beginInWindow;
        String environment = window.substring(beginInWindow + 7, window.length() - 1);
        if (!validEnvironment(environment)) return null;

        String closing = "\\end{" + environment + "}";
        int forwardLimit = Math.min(text.length(), cursor + 2048);
        String forwardWindow = text.subSequence(cursor, forwardLimit).toString();
        if (forwardWindow.contains(closing)) return null;

        int lineStart = begin;
        while (lineStart > searchStart && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int indentEnd = lineStart;
        while (indentEnd < begin) {
            char c = text.charAt(indentEnd);
            if (c != ' ' && c != '\t') break;
            indentEnd++;
        }
        String indent = text.subSequence(lineStart, indentEnd).toString();
        String insertion = "\n" + indent + "  \n" + indent + closing;
        return new EnvironmentExpansion(insertion, 1 + indent.length() + 2);
    }

    private static boolean validEnvironment(String environment) {
        for (int i = 0; i < environment.length(); i++) {
            if (!validEnvironmentChar(environment.charAt(i))) return false;
        }
        return !environment.isEmpty();
    }

    private static boolean validEnvironmentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '@' || c == '*'
                || c == '.' || c == '-';
    }

    private static Command command(String key, String snippet) {
        int caret = snippet.indexOf(CURSOR);
        String insertion = snippet.replace(String.valueOf(CURSOR), "");
        if (caret < 0) caret = insertion.length();
        String label = insertion.replace("\n", " ");
        if (label.length() > 38) label = label.substring(0, 35) + "…";
        return new Command(key.toLowerCase(Locale.ROOT), label, insertion, caret);
    }

    private static final class Command {
        final String key;
        final String label;
        final String insertion;
        final int caretInInsertion;

        Command(String key, String label, String insertion, int caretInInsertion) {
            this.key = key;
            this.label = label;
            this.insertion = insertion;
            this.caretInInsertion = caretInInsertion;
        }
    }

    public static final class Match {
        public final String label;
        public final String insertion;
        public final int replaceStart;
        public final int caretInInsertion;

        Match(String label, String insertion, int replaceStart, int caretInInsertion) {
            this.label = label;
            this.insertion = insertion;
            this.replaceStart = replaceStart;
            this.caretInInsertion = caretInInsertion;
        }
    }

    public static final class EnvironmentExpansion {
        public final String insertion;
        public final int caretInInsertion;

        EnvironmentExpansion(String insertion, int caretInInsertion) {
            this.insertion = insertion;
            this.caretInInsertion = caretInInsertion;
        }
    }
}
