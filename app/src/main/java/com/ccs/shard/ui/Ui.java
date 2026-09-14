package com.ccs.shard.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;

/**
 * Small view helpers used across the app.
 *
 * <p>Most of Shard's chrome is built in code rather than XML: popups, block
 * views and toolbars need to be created and themed dynamically, and building
 * them here avoids inflating a layout file per element — which on a slow device
 * is the difference between a popup that appears instantly and one that stutters.
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static float sp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().scaledDensity;
    }

    /** Resolves a theme attribute to a colour, falling back to magenta when missing. */
    public static int themeColor(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                return androidx.core.content.ContextCompat.getColor(context, value.resourceId);
            }
            return value.data;
        }
        return Color.MAGENTA;
    }

    public static int themeColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                return androidx.core.content.ContextCompat.getColor(context, value.resourceId);
            }
            return value.data;
        }
        return fallback;
    }

    public static int themeDimen(Context context, int attr, int fallbackDp) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            return TypedValue.complexToDimensionPixelSize(
                    value.data, context.getResources().getDisplayMetrics());
        }
        return dp(context, fallbackDp);
    }

    /** Blends {@code overlay} over {@code base} at {@code alpha} (0..1). */
    public static int blend(int base, int overlay, float alpha) {
        float inverse = 1f - alpha;
        int r = Math.round(Color.red(base) * inverse + Color.red(overlay) * alpha);
        int g = Math.round(Color.green(base) * inverse + Color.green(overlay) * alpha);
        int b = Math.round(Color.blue(base) * inverse + Color.blue(overlay) * alpha);
        return Color.argb(255, r, g, b);
    }

    public static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public static boolean isLight(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.55;
    }

    public static GradientDrawable roundRect(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radiusPx);
        drawable.setColor(color);
        return drawable;
    }

    public static GradientDrawable roundRect(int color, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable drawable = roundRect(color, radiusPx);
        if (strokePx > 0) drawable.setStroke(strokePx, strokeColor);
        return drawable;
    }

    /** A ripple over {@code content}, or a simple pressed state pre-Lollipop. */
    public static Drawable ripple(int rippleColor, Drawable content) {
        // A RippleDrawable with no content and no mask is unbounded and can
        // visibly spill over neighbouring list rows. A transparent content
        // layer gives it the bounds of its owning view without changing colour.
        if (content == null) content = new ColorDrawable(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
        }
        return content;
    }

    public static Drawable rippleRect(Context context, int rippleColor, int radiusPx, int fill) {
        return ripple(rippleColor, roundRect(fill, radiusPx));
    }

    public static void setPaddingDp(View view, float left, float top, float right, float bottom) {
        Context c = view.getContext();
        view.setPadding(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
    }

    /** Screen-space bounds of a view, for anchoring popups. */
    public static android.graphics.Rect boundsOnScreen(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new android.graphics.Rect(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    public static void hapticTap(View view) {
        try {
            view.performHapticFeedback(
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) { }
    }

    public static void hapticLongPress(View view) {
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignored) { }
    }

    public static void showKeyboard(View view) {
        view.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(view, 0);
    }

    public static void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
