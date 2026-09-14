package com.ccs.shard.block;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.R;
import com.ccs.shard.ui.Ui;

/**
 * Turns a {@link Block}'s type into typography, spacing and the small leading
 * element beside it — the bullet, the number, the checkbox, the quote bar, the
 * callout icon.
 *
 * <p>All of this used to live inside the adapter's text view-holder, which is
 * where a block editor's line count goes to die. Pulling it out means the rules
 * for "what does a heading look like" are in one readable place, and the holder
 * is left doing what a holder should: wiring input to a model.
 */
public final class BlockStyler {

    /** Callout kinds, in the order the icon cycles through them. */
    private static final String[] CALLOUT_KINDS = {"note", "tip", "warning", "danger"};

    /** Interactions the leading element needs to report back. */
    public interface Callbacks {
        void onToggleChecked(Block block);
        void onCycleCallout(Block block);
    }

    private final Context context;
    private final Callbacks callbacks;

    private float baseTextSize = 16f;
    private boolean monospaceBody;

    public BlockStyler(Context context, Callbacks callbacks) {
        this.context = context;
        this.callbacks = callbacks;
    }

    public void setTypography(float baseTextSize, boolean monospaceBody) {
        this.baseTextSize = baseTextSize;
        this.monospaceBody = monospaceBody;
    }

    /** Per-type typography and padding, resolved once per block type. */
    private static final class Style {
        float sizeMultiplier = 1f;
        int typeface = Typeface.NORMAL;
        boolean mutedColor;
        int topPadDp = 3;
        int bottomPadDp = 3;
        int hintRes;
        float lineSpacing = 1.35f;
    }

    private static Style styleOf(BlockType type) {
        Style style = new Style();
        switch (type) {
            case HEADING_1:
                style.sizeMultiplier = 1.72f;
                style.typeface = Typeface.BOLD;
                style.topPadDp = 18;
                style.bottomPadDp = 4;
                style.hintRes = R.string.hint_heading;
                style.lineSpacing = 1.12f;
                break;
            case HEADING_2:
                style.sizeMultiplier = 1.4f;
                style.typeface = Typeface.BOLD;
                style.topPadDp = 14;
                style.hintRes = R.string.hint_heading;
                style.lineSpacing = 1.12f;
                break;
            case HEADING_3:
                style.sizeMultiplier = 1.18f;
                style.typeface = Typeface.BOLD;
                style.topPadDp = 11;
                style.bottomPadDp = 2;
                style.hintRes = R.string.hint_heading;
                style.lineSpacing = 1.12f;
                break;
            case QUOTE:
                style.typeface = Typeface.ITALIC;
                style.mutedColor = true;
                style.topPadDp = 5;
                style.bottomPadDp = 5;
                break;
            case CALLOUT:
                style.topPadDp = 10;
                style.bottomPadDp = 10;
                break;
            case TODO:
            case BULLET:
            case NUMBERED:
                style.topPadDp = 2;
                style.bottomPadDp = 2;
                break;
            default:
                style.hintRes = R.string.hint_paragraph;
                break;
        }
        return style;
    }

    /**
     * Applies everything visual for one block.
     *
     * @param row      the block's root row, which carries indent and callout tint
     * @param readOnly reading view: no field may take the caret
     */
    public void applyTo(View row, BlockEditText input, Block block, boolean readOnly) {
        int onSurface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        Style style = styleOf(block.type);

        input.setFocusable(!readOnly);
        input.setFocusableInTouchMode(!readOnly);
        input.setCursorVisible(!readOnly);
        input.setLongClickable(!readOnly);
        input.setTextIsSelectable(!readOnly);

        input.setTextSize(baseTextSize * style.sizeMultiplier);
        input.setTypeface(monospaceBody && !block.type.isHeading()
                ? Typeface.MONOSPACE : Typeface.DEFAULT, style.typeface);
        input.setTextColor(style.mutedColor ? variant : onSurface);
        input.setLineSpacing(0f, style.lineSpacing);
        input.setHintTextColor(Ui.withAlpha(variant, 0.55f));
        input.setHint(style.hintRes == 0 ? "" : context.getString(style.hintRes));

        // A completed task reads as done: dimmed and struck through.
        boolean done = block.type == BlockType.TODO && block.checked;
        if (done) input.setTextColor(Ui.withAlpha(onSurface, 0.45f));
        input.setPaintFlags(done
                ? input.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : input.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);

        applyRowPadding(row, block, style);
    }

    private void applyRowPadding(View row, Block block, Style style) {
        int indentPx = Ui.dp(context, 16) * block.indent;
        int top = Ui.dp(context, style.topPadDp);
        int bottom = Ui.dp(context, style.bottomPadDp);

        if (block.type == BlockType.CALLOUT) {
            int tint = calloutColor(block.calloutKind);
            row.setBackground(Ui.roundRect(Ui.withAlpha(tint, 0.10f), Ui.dp(context, 10)));
            row.setPadding(indentPx + Ui.dp(context, 4), top, Ui.dp(context, 10), bottom);
            return;
        }
        row.setBackground(null);
        row.setPadding(indentPx, top, Ui.dp(context, 6), bottom);
    }

    // ---------------------------------------------------------------- leading

    /**
     * Fills the slot left of the text with whatever marks this block's type.
     *
     * @param numberInList position within its own run of numbered items, 1-based
     */
    public void buildLeading(LinearLayout leading, final Block block, int numberInList) {
        leading.removeAllViews();
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);

        switch (block.type) {
            case BULLET:
                leading.addView(bulletView(block, variant));
                break;
            case NUMBERED:
                leading.addView(numberView(numberInList, variant));
                break;
            case TODO:
                leading.addView(checkboxView(block, variant));
                break;
            case QUOTE:
                leading.addView(quoteBar(variant));
                break;
            case CALLOUT:
                leading.addView(calloutIcon(block));
                break;
            default:
                break;
        }
    }

    private View bulletView(Block block, int variant) {
        TextView bullet = new TextView(context);
        bullet.setText(bulletFor(block.indent));
        bullet.setIncludeFontPadding(false);
        bullet.setTypeface(monospaceBody ? Typeface.MONOSPACE : Typeface.DEFAULT);
        bullet.setTextSize(baseTextSize);
        bullet.setTextColor(variant);
        bullet.setGravity(Gravity.CENTER);
        bullet.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(context, 20), ViewGroup.LayoutParams.WRAP_CONTENT));
        return bullet;
    }

    private View numberView(int number, int variant) {
        TextView view = new TextView(context);
        view.setText(number + ".");
        view.setIncludeFontPadding(false);
        view.setTypeface(monospaceBody ? Typeface.MONOSPACE : Typeface.DEFAULT);
        view.setTextSize(baseTextSize * 0.95f);
        view.setTextColor(variant);
        view.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(context, 22), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(context, 4);
        view.setLayoutParams(lp);
        return view;
    }

    private View checkboxView(final Block block, int variant) {
        ImageView checkbox = new ImageView(context);
        checkbox.setImageResource(block.checked
                ? R.drawable.ic_checkbox_checked : R.drawable.ic_checkbox);
        checkbox.setColorFilter(block.checked
                ? Ui.themeColor(context,
                        com.google.android.material.R.attr.colorPrimary, 0xFF2196F3)
                : variant);
        checkbox.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(context, 26), Ui.dp(context, 26));
        lp.rightMargin = Ui.dp(context, 2);
        checkbox.setLayoutParams(lp);
        checkbox.setBackground(Ui.rippleRect(context,
                Ui.withAlpha(variant, 0.2f), Ui.dp(context, 13), 0x00000000));
        checkbox.setContentDescription(context.getString(R.string.toggle_task));
        checkbox.setOnClickListener(v -> {
            Ui.hapticTap(v);
            callbacks.onToggleChecked(block);
        });
        return checkbox;
    }

    private View quoteBar(int variant) {
        View bar = new View(context);
        bar.setBackground(Ui.roundRect(Ui.withAlpha(variant, 0.45f), Ui.dp(context, 2)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(context, 3), Ui.dp(context, 22));
        lp.rightMargin = Ui.dp(context, 12);
        lp.topMargin = Ui.dp(context, 3);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View calloutIcon(final Block block) {
        TextView icon = new TextView(context);
        icon.setText(calloutEmoji(block.calloutKind));
        icon.setTextSize(baseTextSize);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(context, 10);
        icon.setLayoutParams(lp);
        icon.setOnClickListener(v -> callbacks.onCycleCallout(block));
        return icon;
    }

    // ---------------------------------------------------------------- helpers

    /** The kind after {@code current}, so tapping the icon cycles severity. */
    public static String nextCalloutKind(String current) {
        for (int i = 0; i < CALLOUT_KINDS.length; i++) {
            if (CALLOUT_KINDS[i].equals(current)) {
                return CALLOUT_KINDS[(i + 1) % CALLOUT_KINDS.length];
            }
        }
        return CALLOUT_KINDS[0];
    }

    private int calloutColor(String kind) {
        if ("warning".equals(kind)) return 0xFFF59E0B;
        if ("danger".equals(kind)) return 0xFFEF4444;
        if ("tip".equals(kind)) return 0xFF10B981;
        return Ui.themeColor(context,
                com.google.android.material.R.attr.colorPrimary, 0xFF3B82F6);
    }

    private static String calloutEmoji(String kind) {
        if ("warning".equals(kind)) return "⚠️";
        if ("danger".equals(kind)) return "⛔";
        if ("tip".equals(kind)) return "💡";
        return "ℹ️";
    }

    /** Bullet glyph varies with depth, the way a printed outline would. */
    private static String bulletFor(int indent) {
        switch (indent % 3) {
            case 1: return "◦";
            case 2: return "▪";
            default: return "•";
        }
    }
}
