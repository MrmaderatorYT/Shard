package com.ccs.shard.ui;

import android.content.Context;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.R;

/**
 * A row of one-tap symbol keys above the keyboard.
 *
 * <p>Exists because the characters TeX is built from — backslash, braces,
 * brackets — are two or three taps deep on every soft keyboard, which makes
 * writing markup on a tablet genuinely painful. Each key inserts exactly the
 * character it shows, replacing the selection if there is one, so the behaviour
 * is the same as typing it.
 *
 * <p>The key set is {@link #DEFAULT_SYMBOLS}: change that one array to change the
 * bar.
 */
public final class SymbolBar extends HorizontalScrollView {

    /** The characters the bar offers, in order. */
    public static final String[] DEFAULT_SYMBOLS = {
            "\\", "{", "}", "$", "&", "%", "_", "^", "~", "[", "]", "=", "-", "#", "\"", "(", ")",
            "\\item", "\\section", "\\cite", "\\ref", "\\begin{}", "\\end{}"
    };

    private final LinearLayout row;
    private EditText target;
    private String[] symbols = DEFAULT_SYMBOLS;
    private Runnable onInsert;

    public SymbolBar(Context context) {
        this(context, null);
    }

    public SymbolBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setContentDescription(context.getString(R.string.symbols_bar));

        int container = Ui.themeColor(context, R.attr.colorSurfaceContainer,
                Ui.themeColor(context, com.google.android.material.R.attr.colorSurface,
                        0xFFFFFFFF));
        setBackgroundColor(container);

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(row, 6, 5, 6, 5);
        addView(row, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuild();
    }

    /** The field symbols are inserted into. */
    public void setTarget(EditText field) {
        this.target = field;
    }

    /** Called after every insertion, so the host can schedule a save. */
    public void setOnInsert(Runnable action) {
        this.onInsert = action;
    }

    public void setSymbols(String[] values) {
        this.symbols = values == null || values.length == 0 ? DEFAULT_SYMBOLS : values;
        rebuild();
    }

    private void rebuild() {
        row.removeAllViews();
        for (String symbol : symbols) row.addView(key(symbol));
    }

    private View key(final String symbol) {
        Context context = getContext();
        int onSurface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);

        TextView key = new TextView(context);
        key.setText(symbol);
        // Monospace at a generous size: these glyphs are small and easy to
        // mis-hit, and a backslash next to a slash has to be unmistakable.
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setTextSize(15f);
        key.setTextColor(onSurface);
        key.setGravity(Gravity.CENTER);
        key.setMinimumWidth(Ui.dp(context, 44));
        key.setContentDescription(context.getString(R.string.insert_symbol, symbol));
        key.setBackground(Ui.rippleRect(context, Ui.withAlpha(onSurface, 0.14f),
                Ui.dp(context, 9), Ui.withAlpha(onSurface, 0.05f)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(context, 38));
        lp.setMargins(Ui.dp(context, 2), 0, Ui.dp(context, 2), 0);
        key.setLayoutParams(lp);
        Ui.setPaddingDp(key, 10, 0, 10, 0);

        key.setOnClickListener(v -> {
            Ui.hapticTap(v);
            insert(symbol);
        });
        return key;
    }

    private void insert(String symbol) {
        if (target == null) return;
        Editable editable = target.getText();
        if (editable == null) return;
        int start = Math.max(0, target.getSelectionStart());
        int end = Math.max(start, target.getSelectionEnd());
        editable.replace(start, end, symbol);
        int caretOffset = symbol.length();
        if (symbol.equals("\\begin{}")) {
            caretOffset = "\\begin{".length();
        } else if (symbol.equals("$$")) {
            caretOffset = 1;
        }
        target.setSelection(Math.min(editable.length(), start + caretOffset));
        if (onInsert != null) onInsert.run();
    }
}
