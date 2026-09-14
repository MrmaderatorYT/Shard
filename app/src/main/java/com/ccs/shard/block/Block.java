package com.ccs.shard.block;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One editable block in a note.
 *
 * <p>The payload is whichever field the {@link #type} implies: {@link #text} for
 * text blocks, {@link #table} for tables, {@link #imageRef} for images. Keeping
 * them in one class (rather than a subclass per type) means the adapter can
 * convert a block's type in place without losing identity, which is what makes
 * "turn this paragraph into a heading" instant and keeps RecyclerView animations
 * stable.
 */
public final class Block {

    private static final AtomicLong NEXT_UID = new AtomicLong(1);

    /** Stable across type changes; used as the RecyclerView item id. */
    public final long uid;

    public BlockType type;
    /** Raw Markdown source for text blocks, without the block-level marker. */
    public String text = "";
    /** Task-list state for {@link BlockType#TODO}. */
    public boolean checked;
    /** Nesting depth for list blocks (0 = top level). */
    public int indent;
    /** Fence language for {@link BlockType#CODE}; empty means unspecified. */
    public String language = "";
    /** Callout kind for {@link BlockType#CALLOUT}: note, tip, warning, danger. */
    public String calloutKind = "note";
    /** Obsidian callout fold modifier: empty, {@code +} (expanded) or {@code -} (collapsed). */
    public String calloutFold = "";
    public TableData table;
    public String imageRef = "";
    public String imageAlt = "";
    /** Collapsed state for headings; purely a view concern, never serialized. */
    public boolean collapsed;

    public Block(BlockType type) {
        this.uid = NEXT_UID.getAndIncrement();
        this.type = type;
    }

    public static Block text(BlockType type, String text) {
        Block block = new Block(type);
        block.text = text == null ? "" : text;
        return block;
    }

    public static Block paragraph(String text) {
        return text(BlockType.PARAGRAPH, text);
    }

    public static Block todo(String text, boolean checked) {
        Block block = text(BlockType.TODO, text);
        block.checked = checked;
        return block;
    }

    public static Block code(String language, String body) {
        Block block = new Block(BlockType.CODE);
        block.language = language == null ? "" : language;
        block.text = body == null ? "" : body;
        return block;
    }

    public static Block table(TableData data) {
        Block block = new Block(BlockType.TABLE);
        block.table = data != null ? data : TableData.empty(3, 2);
        return block;
    }

    public static Block image(String reference, String alt) {
        Block block = new Block(BlockType.IMAGE);
        block.imageRef = reference == null ? "" : reference;
        block.imageAlt = alt == null ? "" : alt;
        return block;
    }

    public static Block divider() {
        return new Block(BlockType.DIVIDER);
    }

    public static Block callout(String kind, String text) {
        Block block = text(BlockType.CALLOUT, text);
        block.calloutKind = (kind == null || kind.isEmpty()) ? "note" : kind;
        return block;
    }

    /** Preserves an Obsidian transclusion as an editable, standalone block. */
    public static Block embed(String source) {
        return text(BlockType.EMBED, source);
    }

    /** Preserves a full GFM footnote definition, including indented continuation lines. */
    public static Block footnote(String source) {
        return text(BlockType.FOOTNOTE, source);
    }

    /** Preserves a full CommonMark reference-link definition. */
    public static Block reference(String source) {
        return text(BlockType.REFERENCE, source);
    }

    public boolean isEmpty() {
        switch (type) {
            case DIVIDER:
                return false;
            case TABLE:
                return table == null || table.isEmpty();
            case IMAGE:
                return imageRef.isEmpty();
            default:
                return text.trim().isEmpty();
        }
    }

    /** Converts this block in place, preserving text where it makes sense. */
    public void convertTo(BlockType target) {
        if (target == type) return;
        if (target == BlockType.TABLE && table == null) {
            table = TableData.empty(3, 2);
        }
        if (target == BlockType.DIVIDER) {
            text = "";
        }
        if (!target.isText && target != BlockType.CODE) {
            // Text is meaningless for dividers/images/tables.
            if (target != BlockType.TABLE) text = "";
        }
        type = target;
    }

    public Block copy() {
        Block copy = new Block(type);
        copy.text = text;
        copy.checked = checked;
        copy.indent = indent;
        copy.language = language;
        copy.calloutKind = calloutKind;
        copy.calloutFold = calloutFold;
        copy.table = table != null ? table.copy() : null;
        copy.imageRef = imageRef;
        copy.imageAlt = imageAlt;
        return copy;
    }

    @Override
    public String toString() {
        return "Block{" + type + " '" + (text.length() > 24 ? text.substring(0, 24) : text) + "'}";
    }
}
