package com.ccs.shard.ui;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.ccs.shard.R;

/**
 * A small text editor that appears beside what it edits.
 *
 * <p>Used for canvas card text and other short edits where a full-screen dialog
 * would hide the thing being changed. Same positioning rules as
 * {@link AnchoredMenu}: it opens near the anchor and flips to stay on screen.
 */
public final class InlineTextPrompt {

    public interface OnResult {
        void onText(String text);
    }

    private InlineTextPrompt() {}

    /** Shows the editor at a point in {@code anchor}'s coordinate space. */
    public static void show(final View anchor, CharSequence title, String initial,
                            boolean multiline, float x, float y, final OnResult onResult) {
        final Context context = anchor.getContext();
        int surface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        int elevated = Ui.blend(surface, Ui.isLight(surface) ? 0xFF000000 : 0xFFFFFFFF, 0.05f);
        int outline = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0x1F000000);
        int onSurface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF787066);
        int primary = Ui.themeColor(context,
                com.google.android.material.R.attr.colorPrimary, 0xFF2F6FEB);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundRect(elevated, Ui.dp(context, 14),
                outline, Ui.dp(context, 1)));
        Ui.setPaddingDp(card, 14, 12, 14, 10);

        if (title != null) {
            TextView label = new TextView(context);
            label.setText(title);
            label.setTextSize(11f);
            label.setAllCaps(true);
            label.setLetterSpacing(0.06f);
            label.setTextColor(variant);
            label.setPadding(0, 0, 0, Ui.dp(context, 8));
            card.addView(label);
        }

        final EditText input = new EditText(context);
        input.setText(initial == null ? "" : initial);
        input.setTextSize(14.5f);
        input.setTextColor(onSurface);
        input.setBackground(Ui.roundRect(Ui.withAlpha(onSurface, 0.06f), Ui.dp(context, 8)));
        Ui.setPaddingDp(input, 10, 8, 10, 8);
        input.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(!multiline);
        input.setMinLines(multiline ? 3 : 1);
        input.setMaxLines(multiline ? 6 : 1);
        input.setSelection(input.getText().length());
        card.addView(input, new LinearLayout.LayoutParams(
                Ui.dp(context, 260), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Ui.dp(context, 8), 0, 0);

        final PopupWindow[] holder = new PopupWindow[1];

        TextView cancel = actionButton(context, context.getString(R.string.cancel), variant);
        cancel.setOnClickListener(v -> holder[0].dismiss());
        buttons.addView(cancel);

        TextView save = actionButton(context, context.getString(R.string.save), primary);
        save.setOnClickListener(v -> {
            String text = input.getText().toString();
            holder[0].dismiss();
            if (onResult != null) onResult.onText(text);
        });
        buttons.addView(save);
        card.addView(buttons);

        PopupWindow window = new PopupWindow(card,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        holder[0] = window;
        window.setBackgroundDrawable(null);
        window.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setElevation(Ui.dp(context, 14));
        }
        // Resize with the keyboard so the field is never covered.
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        card.measure(unspecified, unspecified);
        int width = card.getMeasuredWidth();
        int height = card.getMeasuredHeight();

        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        Rect visible = new Rect();
        anchor.getRootView().getWindowVisibleDisplayFrame(visible);
        int margin = Ui.dp(context, 8);
        int screenX = location[0] + Math.round(x);
        int screenY = location[1] + Math.round(y);
        if (screenX + width > visible.right - margin) screenX = visible.right - width - margin;
        if (screenX < visible.left + margin) screenX = visible.left + margin;
        if (screenY + height > visible.bottom - margin) {
            screenY = Math.max(visible.top + margin, visible.bottom - height - margin);
        }

        window.showAtLocation(anchor, Gravity.NO_GRAVITY, screenX, screenY);
        input.requestFocus();
        input.post(() -> Ui.showKeyboard(input));
    }

    private static TextView actionButton(Context context, CharSequence label, int color) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(14f);
        view.setTextColor(color);
        view.setAllCaps(false);
        Ui.setPaddingDp(view, 12, 8, 12, 8);
        view.setBackground(Ui.rippleRect(context, Ui.withAlpha(color, 0.16f),
                Ui.dp(context, 8), 0x00000000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(context, 4);
        view.setLayoutParams(lp);
        return view;
    }
}
