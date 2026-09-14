package com.ccs.shard.block;

import com.ccs.shard.R;

/**
 * Maps a {@link BlockType} to the string and icon that name it in the UI.
 *
 * <p>Pure resource lookup with no {@code Context}, so menus, the slash palette
 * and the block inspector can all label a block the same way without each
 * keeping its own copy of the switch — which is how "Callout" ended up worded
 * differently in two places before.
 */
public final class BlockTypeUi {

    private BlockTypeUi() {}

    public static int nameRes(BlockType type) {
        switch (type) {
            case HEADING_1: return R.string.cmd_h1;
            case HEADING_2: return R.string.cmd_h2;
            case HEADING_3: return R.string.cmd_h3;
            case BULLET: return R.string.cmd_bullet;
            case NUMBERED: return R.string.cmd_numbered;
            case TODO: return R.string.cmd_todo;
            case QUOTE: return R.string.cmd_quote;
            case CALLOUT: return R.string.cmd_callout;
            case CODE: return R.string.cmd_code;
            case TABLE: return R.string.cmd_table;
            case IMAGE: return R.string.cmd_image;
            case DIVIDER: return R.string.cmd_divider;
            default: return R.string.cmd_text;
        }
    }

    public static int iconRes(BlockType type) {
        switch (type) {
            case HEADING_1: return R.drawable.ic_block_h1;
            case HEADING_2: return R.drawable.ic_block_h2;
            case HEADING_3: return R.drawable.ic_block_h3;
            case BULLET: return R.drawable.ic_block_bullet;
            case NUMBERED: return R.drawable.ic_block_numbered;
            case TODO: return R.drawable.ic_block_todo;
            case QUOTE: return R.drawable.ic_block_quote;
            case CALLOUT: return R.drawable.ic_block_callout;
            case CODE: return R.drawable.ic_block_code;
            case TABLE: return R.drawable.ic_block_table;
            case IMAGE: return R.drawable.ic_block_image;
            case DIVIDER: return R.drawable.ic_block_divider;
            default: return R.drawable.ic_block_text;
        }
    }

    /** The types offered by "Turn into", in presentation order. */
    public static BlockType[] convertibleTypes() {
        return new BlockType[]{
                BlockType.PARAGRAPH, BlockType.HEADING_1, BlockType.HEADING_2,
                BlockType.HEADING_3, BlockType.BULLET, BlockType.NUMBERED,
                BlockType.TODO, BlockType.QUOTE, BlockType.CALLOUT,
                BlockType.CODE, BlockType.TABLE, BlockType.DIVIDER,
        };
    }
}
