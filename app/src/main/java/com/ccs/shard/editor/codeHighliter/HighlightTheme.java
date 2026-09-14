package com.ccs.shard.editor.codeHighliter;

public class HighlightTheme {
    public final int keyword;
    public final int string;
    public final int number;
    public final int comment;
    public final int type;
    public final int function;
    public final int annotation;
    public final int constant;
    public final int preprocessor;
    public final int tag;
    public final int attr;
    public final int operator;
    public final int param;
    public final int background;

    private HighlightTheme(int keyword, int string, int number, int comment, int type,
                           int function, int annotation, int constant, int preprocessor,
                           int tag, int attr, int operator, int param, int background) {
        this.keyword = keyword;
        this.string = string;
        this.number = number;
        this.comment = comment;
        this.type = type;
        this.function = function;
        this.annotation = annotation;
        this.constant = constant;
        this.preprocessor = preprocessor;
        this.tag = tag;
        this.attr = attr;
        this.operator = operator;
        this.param = param;
        this.background = background;
    }

    public static final HighlightTheme VS_CODE_DARK = new HighlightTheme(
        0xFF569CD6, 0xFFCE9178, 0xFFB5CEA8, 0xFF6A9955,
        0xFF4EC9B0, 0xFFDCDCAA, 0xFFD4D4D4, 0xFF4FC1FF,
        0xFFC586C0, 0xFF569CD6, 0xFF9CDCFE, 0xFFD4D4D4,
        0xFF9CDCFE, 0xFF1E1E1E
    );

    public static final HighlightTheme MONOKAI = new HighlightTheme(
        0xFFF92672, 0xFFE6DB74, 0xFFAE81FF, 0xFF75715E,
        0xFFA6E22E, 0xFFA6E22E, 0xFFF8F8F2, 0xFF66D9EF,
        0xFFF92672, 0xFFF92672, 0xFFA6E22E, 0xFFF8F8F2,
        0xFFF8F8F2, 0xFF272822
    );

    public static final HighlightTheme SOLARIZED_DARK = new HighlightTheme(
        0xFF859900, 0xFF2AA198, 0xFF268BD2, 0xFF586E75,
        0xFFB58900, 0xFF268BD2, 0xFF93A1A1, 0xFFCB4B16,
        0xFF859900, 0xFF268BD2, 0xFF859900, 0xFF93A1A1,
        0xFF93A1A1, 0xFF002B36
    );

    public static final HighlightTheme GITHUB_DARK = new HighlightTheme(
        0xFFFF7B72, 0xFFA5D6FF, 0xFF79C0FF, 0xFF8B949E,
        0xFFFFA657, 0xFFD2A8FF, 0xFF8B949E, 0xFF79C0FF,
        0xFFFF7B72, 0xFF7EE787, 0xFF79C0FF, 0xFFC9D1D9,
        0xFFC9D1D9, 0xFF0D1117
    );

    public static final HighlightTheme NOTION_LIGHT = new HighlightTheme(
        0xFF0B6E99, 0xFF9F4F00, 0xFF8F4A00, 0xFF787774,
        0xFF0F7B6C, 0xFF805AD5, 0xFFB45309, 0xFFB45309,
        0xFF6941C6, 0xFF0B6E99, 0xFF6C5DD3, 0xFF37352F,
        0xFF57606A, 0xFFF7F6F3
    );

    private static final HighlightTheme[] ALL = {
        VS_CODE_DARK, MONOKAI, SOLARIZED_DARK, GITHUB_DARK
    };

    private static final String[] NAMES = {
        "VS Code Dark+", "Monokai", "Solarized Dark", "GitHub Dark"
    };

    public static HighlightTheme[] getAll() { return ALL; }
    public static String[] getNames() { return NAMES; }
    public static HighlightTheme getByIndex(int index) {
        if (index < 0 || index >= ALL.length) return VS_CODE_DARK;
        return ALL[index];
    }
}
