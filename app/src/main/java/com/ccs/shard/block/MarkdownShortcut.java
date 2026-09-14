package com.ccs.shard.block;

/**
 * The "type {@code ## } and the line becomes a heading" table.
 *
 * <p>A declarative list rather than a chain of {@code else if}s, and free of any
 * view or adapter dependency, so the mapping can be read — and tested — on its
 * own. Adding a shortcut is one row.
 */
public final class MarkdownShortcut {

    /** Longest trigger, used to bail out early on ordinary typing. */
    private static final int MAX_TRIGGER_LENGTH = 4;

    private static final MarkdownShortcut[] TABLE = {
            of("# ", BlockType.HEADING_1),
            of("## ", BlockType.HEADING_2),
            of("### ", BlockType.HEADING_3),
            of("- ", BlockType.BULLET),
            of("* ", BlockType.BULLET),
            of("+ ", BlockType.BULLET),
            of("1. ", BlockType.NUMBERED),
            of("1) ", BlockType.NUMBERED),
            of("[] ", BlockType.TODO),
            of("[ ] ", BlockType.TODO),
            checked("[x] "),
            of("> ", BlockType.QUOTE),
            of("```", BlockType.CODE),
            of("--- ", BlockType.DIVIDER),
            of("*** ", BlockType.DIVIDER),
    };

    /** Characters the user typed that turn into the block's formatting. */
    public final String trigger;
    public final BlockType target;
    /** True for {@code [x] }, which produces an already-completed task. */
    public final boolean checked;

    private MarkdownShortcut(String trigger, BlockType target, boolean checked) {
        this.trigger = trigger;
        this.target = target;
        this.checked = checked;
    }

    private static MarkdownShortcut of(String trigger, BlockType target) {
        return new MarkdownShortcut(trigger, target, false);
    }

    private static MarkdownShortcut checked(String trigger) {
        return new MarkdownShortcut(trigger, BlockType.TODO, true);
    }

    /** Number of characters to remove from the block's text. */
    public int consumed() {
        return trigger.length();
    }

    /**
     * Matches the whole of {@code text} against the table.
     *
     * @return the shortcut, or null when the text is not a trigger
     */
    public static MarkdownShortcut match(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TRIGGER_LENGTH) return null;
        for (MarkdownShortcut shortcut : TABLE) {
            if (shortcut.trigger.equals(text)) return shortcut;
        }
        return null;
    }
}
