package com.ccs.shard.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.R;

/**
 * The bar above the keyboard while editing.
 *
 * <p>Deliberately short. The previous editor put twelve Markdown syntax buttons
 * in a row, which taught nothing and hid the useful ones; here the first button
 * opens the same {@code /} palette the keyboard shortcut does, so the discoverable
 * path and the fast path are the same thing. The rest are the operations that are
 * genuinely awkward without a button: inline emphasis, list indenting, and undo.
 */
public final class FormatBar extends HorizontalScrollView {

    public static final int ACTION_INSERT_BLOCK = 1;
    public static final int ACTION_BOLD = 2;
    public static final int ACTION_ITALIC = 3;
    public static final int ACTION_STRIKE = 4;
    public static final int ACTION_CODE = 5;
    public static final int ACTION_LINK = 6;
    public static final int ACTION_WIKI_LINK = 7;
    public static final int ACTION_TODO = 8;
    public static final int ACTION_BULLET = 9;
    public static final int ACTION_OUTDENT = 10;
    public static final int ACTION_INDENT = 11;
    public static final int ACTION_UNDO = 12;
    public static final int ACTION_REDO = 13;
    public static final int ACTION_DONE = 14;

    /** Receives a raw symbol key press. */
    public interface OnSymbol {
        void onSymbol(String symbol);
    }

    public interface Listener {
        void onFormatAction(int action);
    }

    private final LinearLayout row;
    private ImageView undoButton;
    private ImageView redoButton;
    private Listener listener;
    private OnSymbol onSymbol;

    public FormatBar(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);

        int surface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        int container = Ui.themeColor(context, R.attr.colorSurfaceContainer, surface);
        setBackgroundColor(container);

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(row, 6, 4, 6, 4);
        addView(row, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        add(ACTION_INSERT_BLOCK, R.drawable.ic_add, R.string.slash_menu_title, true);
        addSeparator();
        add(ACTION_BOLD, R.drawable.ic_format_bold, R.string.format_bold, false);
        add(ACTION_ITALIC, R.drawable.ic_format_italic, R.string.format_italic, false);
        add(ACTION_STRIKE, R.drawable.ic_format_strikethrough, R.string.format_strike, false);
        add(ACTION_CODE, R.drawable.ic_format_code, R.string.format_code, false);
        addSeparator();
        add(ACTION_WIKI_LINK, R.drawable.ic_wiki_link, R.string.cmd_link_note, false);
        add(ACTION_LINK, R.drawable.ic_format_link, R.string.format_link, false);
        addSeparator();
        add(ACTION_TODO, R.drawable.ic_block_todo, R.string.cmd_todo, false);
        add(ACTION_BULLET, R.drawable.ic_block_bullet, R.string.cmd_bullet, false);
        add(ACTION_OUTDENT, R.drawable.ic_arrow_left, R.string.outdent, false);
        add(ACTION_INDENT, R.drawable.ic_arrow_right, R.string.indent, false);
        addSeparator();
        undoButton = add(ACTION_UNDO, R.drawable.ic_undo, R.string.undo, false);
        redoButton = add(ACTION_REDO, R.drawable.ic_redo, R.string.redo, false);
        add(ACTION_DONE, R.drawable.ic_keyboard_hide, R.string.hide_keyboard, false);
        // Raw symbol keys last: they are an escape hatch for characters the soft
        // keyboard buries, not a primary formatting control.
        addSeparator();
        for (String symbol : SymbolBar.DEFAULT_SYMBOLS) row.addView(symbolKey(symbol));
        setHistoryState(false, false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setOnSymbol(OnSymbol listener) {
        this.onSymbol = listener;
    }

    /** A monospace key that inserts one literal character. */
    private View symbolKey(final String symbol) {
        int onSurface = Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);

        TextView key = new TextView(getContext());
        key.setText(symbol);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setTextSize(16f);
        key.setTextColor(Ui.withAlpha(onSurface, 0.85f));
        key.setGravity(Gravity.CENTER);
        key.setContentDescription(getContext().getString(R.string.insert_symbol, symbol));
        key.setBackground(Ui.rippleRect(getContext(), Ui.withAlpha(onSurface, 0.14f),
                Ui.dp(getContext(), 9), Ui.withAlpha(onSurface, 0.05f)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 38), Ui.dp(getContext(), 40));
        lp.setMargins(Ui.dp(getContext(), 1), 0, Ui.dp(getContext(), 1), 0);
        key.setLayoutParams(lp);

        key.setOnClickListener(v -> {
            Ui.hapticTap(v);
            if (onSymbol != null) onSymbol.onSymbol(symbol);
        });
        return key;
    }

    public void setHistoryState(boolean canUndo, boolean canRedo) {
        undoButton.setEnabled(canUndo);
        undoButton.setAlpha(canUndo ? 1f : 0.3f);
        redoButton.setEnabled(canRedo);
        redoButton.setAlpha(canRedo ? 1f : 0.3f);
    }

    private ImageView add(final int action, int iconRes, int descriptionRes, boolean accent) {
        int onSurface = Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);
        int primary = Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorPrimary, 0xFF2F6FEB);

        ImageView button = new ImageView(getContext());
        button.setImageResource(iconRes);
        button.setColorFilter(accent ? primary : Ui.withAlpha(onSurface, 0.75f));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(getContext().getString(descriptionRes));
        button.setBackground(Ui.rippleRect(getContext(), Ui.withAlpha(onSurface, 0.12f),
                Ui.dp(getContext(), 9),
                accent ? Ui.withAlpha(primary, 0.12f) : 0x00000000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 42), Ui.dp(getContext(), 40));
        lp.setMargins(Ui.dp(getContext(), 1), 0, Ui.dp(getContext(), 1), 0);
        button.setLayoutParams(lp);
        Ui.setPaddingDp(button, 9, 9, 9, 9);
        button.setOnClickListener(v -> {
            Ui.hapticTap(v);
            if (listener != null) listener.onFormatAction(action);
        });
        row.addView(button);
        return button;
    }

    private void addSeparator() {
        View line = new View(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 1), Ui.dp(getContext(), 20));
        lp.setMargins(Ui.dp(getContext(), 5), 0, Ui.dp(getContext(), 5), 0);
        line.setLayoutParams(lp);
        line.setBackgroundColor(Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOutlineVariant, 0x22808080));
        row.addView(line);
    }
}
