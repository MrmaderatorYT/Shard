package com.ccs.shard.ui;

import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/** Non-focusable autocomplete popup that keeps the editor keyboard and caret active. */
public final class InlineSuggestionPopup {

    public interface Listener { void onSelected(int position); }

    private final android.content.Context context;
    private final LinearLayout rows;
    private final PopupWindow window;

    public InlineSuggestionPopup(android.content.Context context) {
        this.context = context;
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        int surface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        int outline = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0x22000000);
        rows.setBackground(Ui.roundRect(surface, Ui.dp(context, 13), outline, Ui.dp(context, 1)));
        Ui.setPaddingDp(rows, 4, 5, 4, 5);

        int width = Math.min(Ui.dp(context, 320),
                context.getResources().getDisplayMetrics().widthPixels - Ui.dp(context, 24));
        window = new PopupWindow(rows, width, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        window.setOutsideTouchable(false);
        window.setClippingEnabled(true);
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setElevation(Ui.dp(context, 10));
        }
    }

    public boolean isShowing() { return window.isShowing(); }

    public void dismiss() { window.dismiss(); }

    public void show(View anchor, Rect caretOnScreen, List<String> labels,
                     List<String> hints, Listener listener) {
        rows.removeAllViews();
        int onSurface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        for (int i = 0; i < labels.size(); i++) {
            final int position = i;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setMinimumHeight(Ui.dp(context, 44));
            Ui.setPaddingDp(row, 12, 6, 12, 6);
            row.setBackground(Ui.rippleRect(context, Ui.withAlpha(onSurface, 0.10f),
                    Ui.dp(context, 9), 0x00000000));

            TextView label = new TextView(context);
            label.setText(labels.get(i));
            label.setSingleLine(true);
            label.setTextSize(14.5f);
            label.setTextColor(onSurface);
            row.addView(label);
            String hintText = hints != null && i < hints.size() ? hints.get(i) : null;
            if (hintText != null && !hintText.isEmpty()) {
                TextView hint = new TextView(context);
                hint.setText(hintText);
                hint.setSingleLine(true);
                hint.setTextSize(11.5f);
                hint.setTextColor(variant);
                row.addView(hint);
            }
            row.setOnClickListener(v -> listener.onSelected(position));
            rows.addView(row);
        }

        rows.measure(
                View.MeasureSpec.makeMeasureSpec(window.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Ui.dp(context, 330), View.MeasureSpec.AT_MOST));
        int height = rows.getMeasuredHeight();
        Rect visible = new Rect();
        anchor.getRootView().getWindowVisibleDisplayFrame(visible);
        int x = Math.max(visible.left + Ui.dp(context, 8), caretOnScreen.left);
        if (x + window.getWidth() > visible.right - Ui.dp(context, 8)) {
            x = visible.right - window.getWidth() - Ui.dp(context, 8);
        }
        int y = caretOnScreen.bottom + Ui.dp(context, 5);
        if (y + height > visible.bottom - Ui.dp(context, 8)) {
            y = Math.max(visible.top + Ui.dp(context, 8),
                    caretOnScreen.top - height - Ui.dp(context, 5));
        }
        if (window.isShowing()) {
            window.update(x, y, window.getWidth(), height);
        } else {
            window.setHeight(height);
            window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        }
    }
}
