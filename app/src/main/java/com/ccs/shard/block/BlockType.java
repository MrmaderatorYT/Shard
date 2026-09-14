package com.ccs.shard.block;

/**
 * The kinds of block the editor understands.
 *
 * <p>Deliberately closed: this is a Markdown editor with a block UI, not a
 * general document model. Everything here round-trips to plain CommonMark (plus
 * the two widely-supported extensions: task lists and pipe tables), so a note
 * written in Shard opens correctly in Obsidian, GitHub or any other viewer.
 */
public enum BlockType {

    PARAGRAPH(true),
    HEADING_1(true),
    HEADING_2(true),
    HEADING_3(true),
    BULLET(true),
    NUMBERED(true),
    TODO(true),
    QUOTE(true),
    CALLOUT(true),
    /** Obsidian transclusion such as {@code ![[Project brief#Scope|Brief]]}. */
    EMBED(true),
    /** A GFM definition such as {@code [^source]: Citation text}. */
    FOOTNOTE(true),
    /** A CommonMark reference definition such as {@code [docs]: https://…}. */
    REFERENCE(true),
    CODE(false),
    TABLE(false),
    IMAGE(false),
    DIVIDER(false);

    /** True when the block's payload is inline-formatted rich text. */
    public final boolean isText;

    BlockType(boolean isText) {
        this.isText = isText;
    }

    public boolean isHeading() {
        return this == HEADING_1 || this == HEADING_2 || this == HEADING_3;
    }

    public boolean isList() {
        return this == BULLET || this == NUMBERED || this == TODO;
    }

    /** Definitions need to stay adjacent when serialised. */
    public boolean isDefinition() {
        return this == FOOTNOTE || this == REFERENCE;
    }

    /** Headings and list items keep their type when the user presses Enter. */
    public BlockType typeForNextBlock() {
        if (isList()) return this;
        return PARAGRAPH;
    }
}
