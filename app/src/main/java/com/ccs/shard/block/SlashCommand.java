package com.ccs.shard.block;

import com.ccs.shard.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /} command palette entries.
 *
 * <p>Each command carries its own keyword list so the palette matches what the
 * user actually types, in either interface language — {@code /tab}, {@code /таб}
 * and {@code /table} all find the table command. Matching is prefix-first so the
 * obvious command wins over an incidental substring hit.
 */
public final class SlashCommand {

    public static final int ID_TEXT = 1;
    public static final int ID_H1 = 2;
    public static final int ID_H2 = 3;
    public static final int ID_H3 = 4;
    public static final int ID_BULLET = 5;
    public static final int ID_NUMBERED = 6;
    public static final int ID_TODO = 7;
    public static final int ID_TABLE = 8;
    public static final int ID_CODE = 9;
    public static final int ID_QUOTE = 10;
    public static final int ID_CALLOUT = 11;
    public static final int ID_DIVIDER = 12;
    public static final int ID_IMAGE = 13;
    public static final int ID_LINK_NOTE = 14;
    public static final int ID_DATE = 15;
    public static final int ID_TAG = 16;

    public final int id;
    public final int iconRes;
    public final int titleRes;
    public final int subtitleRes;
    private final String[] keywords;

    private SlashCommand(int id, int iconRes, int titleRes, int subtitleRes, String... keywords) {
        this.id = id;
        this.iconRes = iconRes;
        this.titleRes = titleRes;
        this.subtitleRes = subtitleRes;
        this.keywords = keywords;
    }

    /** Full palette, in the order it is presented. */
    public static List<SlashCommand> all() {
        List<SlashCommand> out = new ArrayList<>(16);
        out.add(new SlashCommand(ID_TEXT, R.drawable.ic_block_text,
                R.string.cmd_text, R.string.cmd_text_hint,
                "text", "paragraph", "p", "текст", "абзац"));
        out.add(new SlashCommand(ID_H1, R.drawable.ic_block_h1,
                R.string.cmd_h1, R.string.cmd_h1_hint,
                "h1", "heading1", "title", "заголовок1", "заголовок"));
        out.add(new SlashCommand(ID_H2, R.drawable.ic_block_h2,
                R.string.cmd_h2, R.string.cmd_h2_hint,
                "h2", "heading2", "subtitle", "заголовок2", "підзаголовок"));
        out.add(new SlashCommand(ID_H3, R.drawable.ic_block_h3,
                R.string.cmd_h3, R.string.cmd_h3_hint,
                "h3", "heading3", "заголовок3"));
        out.add(new SlashCommand(ID_TODO, R.drawable.ic_block_todo,
                R.string.cmd_todo, R.string.cmd_todo_hint,
                "todo", "task", "checkbox", "check", "завдання", "чеклист", "справа"));
        out.add(new SlashCommand(ID_BULLET, R.drawable.ic_block_bullet,
                R.string.cmd_bullet, R.string.cmd_bullet_hint,
                "bullet", "list", "ul", "список", "перелік"));
        out.add(new SlashCommand(ID_NUMBERED, R.drawable.ic_block_numbered,
                R.string.cmd_numbered, R.string.cmd_numbered_hint,
                "numbered", "ol", "ordered", "number", "нумерований", "номер"));
        out.add(new SlashCommand(ID_TABLE, R.drawable.ic_block_table,
                R.string.cmd_table, R.string.cmd_table_hint,
                "table", "grid", "таблиця", "табл"));
        out.add(new SlashCommand(ID_CODE, R.drawable.ic_block_code,
                R.string.cmd_code, R.string.cmd_code_hint,
                "code", "snippet", "pre", "код", "програма"));
        out.add(new SlashCommand(ID_QUOTE, R.drawable.ic_block_quote,
                R.string.cmd_quote, R.string.cmd_quote_hint,
                "quote", "blockquote", "цитата"));
        out.add(new SlashCommand(ID_CALLOUT, R.drawable.ic_block_callout,
                R.string.cmd_callout, R.string.cmd_callout_hint,
                "callout", "note", "warning", "tip", "info", "виноска", "нотатка", "увага"));
        out.add(new SlashCommand(ID_DIVIDER, R.drawable.ic_block_divider,
                R.string.cmd_divider, R.string.cmd_divider_hint,
                "divider", "hr", "rule", "separator", "розділювач", "лінія"));
        out.add(new SlashCommand(ID_IMAGE, R.drawable.ic_block_image,
                R.string.cmd_image, R.string.cmd_image_hint,
                "image", "photo", "picture", "img", "зображення", "фото", "картинка"));
        out.add(new SlashCommand(ID_LINK_NOTE, R.drawable.ic_block_link,
                R.string.cmd_link_note, R.string.cmd_link_note_hint,
                "link", "wiki", "mention", "note", "посилання", "нотатка", "лінк"));
        out.add(new SlashCommand(ID_TAG, R.drawable.ic_block_tag,
                R.string.cmd_tag, R.string.cmd_tag_hint,
                "tag", "hashtag", "тег", "мітка"));
        out.add(new SlashCommand(ID_DATE, R.drawable.ic_block_date,
                R.string.cmd_date, R.string.cmd_date_hint,
                "date", "today", "now", "дата", "сьогодні"));
        return out;
    }

    /**
     * Ranks this command against a query.
     *
     * @return a score, higher is better; 0 means no match
     */
    public int score(String query, String localizedTitle) {
        if (query.isEmpty()) return 1;
        String q = query.toLowerCase(Locale.ROOT);
        int best = 0;
        String title = localizedTitle == null ? "" : localizedTitle.toLowerCase(Locale.ROOT);
        if (title.startsWith(q)) best = Math.max(best, 100);
        else if (title.contains(q)) best = Math.max(best, 60);
        for (String keyword : keywords) {
            if (keyword.equals(q)) best = Math.max(best, 120);
            else if (keyword.startsWith(q)) best = Math.max(best, 90);
            else if (keyword.contains(q)) best = Math.max(best, 40);
        }
        return best;
    }

    /** The block this command produces, or null when it inserts inline text instead. */
    public BlockType blockType() {
        switch (id) {
            case ID_TEXT: return BlockType.PARAGRAPH;
            case ID_H1: return BlockType.HEADING_1;
            case ID_H2: return BlockType.HEADING_2;
            case ID_H3: return BlockType.HEADING_3;
            case ID_BULLET: return BlockType.BULLET;
            case ID_NUMBERED: return BlockType.NUMBERED;
            case ID_TODO: return BlockType.TODO;
            case ID_TABLE: return BlockType.TABLE;
            case ID_CODE: return BlockType.CODE;
            case ID_QUOTE: return BlockType.QUOTE;
            case ID_CALLOUT: return BlockType.CALLOUT;
            case ID_DIVIDER: return BlockType.DIVIDER;
            case ID_IMAGE: return BlockType.IMAGE;
            default: return null;
        }
    }
}
