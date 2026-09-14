package com.ccs.shard.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A small floating menu that appears <em>next to</em> what it acts on.
 *
 * <p>This is the app's answer to modal dialogs: long-pressing a block, a table
 * border or a selection opens one of these beside the touch point instead of
 * dimming the screen and stealing focus. It measures itself, then picks a side
 * (below, above, left or right of the anchor) that keeps it fully on screen, so
 * it works the same on a phone in portrait and on a tablet in landscape.
 *
 * <p>Two shapes:
 * <ul>
 *   <li>{@link #vertical(Context)} — a compact action list.</li>
 *   <li>{@link #horizontal(Context)} — an icon strip, used for the selection
 *       format bar and table row/column controls.</li>
 * </ul>
 */
public final class AnchoredMenu {

    /** One row (or icon) in the menu. */
    public static final class Item {
        final int id;
        final int iconRes;
        final CharSequence label;
        CharSequence hint;
        boolean destructive;
        boolean checked;
        boolean enabled = true;

        public Item(int id, int iconRes, CharSequence label) {
            this.id = id;
            this.iconRes = iconRes;
            this.label = label;
        }

        public Item hint(CharSequence value) { this.hint = value; return this; }

        public Item destructive() { this.destructive = true; return this; }

        public Item checked(boolean value) { this.checked = value; return this; }

        public Item enabled(boolean value) { this.enabled = value; return this; }
    }

    public interface OnItemClick {
        void onItem(int id);
    }

    private static final int DIVIDER_ID = -0x7FFF;

    private final Context context;
    private final boolean horizontal;
    private final List<Item> items = new ArrayList<>();
    private CharSequence title;
    private OnItemClick listener;
    private PopupWindow window;
    private Runnable onDismiss;

    private AnchoredMenu(Context context, boolean horizontal) {
        this.context = context;
        this.horizontal = horizontal;
    }

    public static AnchoredMenu vertical(Context context) {
        return new AnchoredMenu(context, false);
    }

    public static AnchoredMenu horizontal(Context context) {
        return new AnchoredMenu(context, true);
    }

    public AnchoredMenu title(CharSequence value) {
        this.title = value;
        return this;
    }

    public AnchoredMenu add(Item item) {
        items.add(item);
        return this;
    }

    public AnchoredMenu add(int id, int iconRes, CharSequence label) {
        return add(new Item(id, iconRes, label));
    }

    public AnchoredMenu divider() {
        items.add(new Item(DIVIDER_ID, 0, null));
        return this;
    }

    public AnchoredMenu onItem(OnItemClick listener) {
        this.listener = listener;
        return this;
    }

    public AnchoredMenu onDismiss(Runnable action) {
        this.onDismiss = action;
        return this;
    }

    public boolean isShowing() {
        return window != null && window.isShowing();
    }

    public void dismiss() {
        if (window != null) window.dismiss();
    }

    // ---------------------------------------------------------------- showing

    /** Shows the menu beside {@code anchor}. */
    public void showAt(View anchor) {
        showAtRect(anchor, Ui.boundsOnScreen(anchor));
    }

    /**
     * Shows the menu beside a point, given in {@code anchor}'s coordinate space.
     * Used for long-press gestures where the meaningful position is the finger,
     * not the whole view.
     */
    public void showAtPoint(View anchor, float x, float y) {
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int screenX = location[0] + Math.round(x);
        int screenY = location[1] + Math.round(y);
        showAtRect(anchor, new Rect(screenX, screenY, screenX, screenY));
    }

    /** Shows the menu beside an arbitrary screen-space rectangle. */
    public void showAtRect(View anchor, Rect anchorRect) {
        View content = buildContent();
        window = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        window.setBackgroundDrawable(null);
        window.setOutsideTouchable(true);
        window.setClippingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setElevation(Ui.dp(context, 12));
        }
        window.setAnimationStyle(android.R.style.Animation_Dialog);
        if (onDismiss != null) {
            window.setOnDismissListener(new PopupWindow.OnDismissListener() {
                @Override public void onDismiss() { onDismiss.run(); }
            });
        }

        // Measure with an UNSPECIFIED width, then pin the popup to the result.
        // A wrap-content LinearLayout containing MATCH_PARENT children (the
        // dividers) reports the full parent width under an AT_MOST spec, which
        // stretched the menu across the whole screen. Measuring unconstrained
        // gives the width the content actually wants; fixing the popup to it then
        // lets the dividers span the menu properly.
        int maxWidth = anchor.getResources().getDisplayMetrics().widthPixels
                - Ui.dp(context, 24);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        content.measure(unspecified, unspecified);
        int width = Math.max(Ui.dp(context, 200),
                Math.min(content.getMeasuredWidth(), maxWidth));
        content.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                unspecified);
        int height = content.getMeasuredHeight();
        window.setWidth(width);

        Rect visible = new Rect();
        anchor.getRootView().getWindowVisibleDisplayFrame(visible);
        int margin = Ui.dp(context, 8);

        int x = anchorRect.left;
        int y = anchorRect.bottom + margin;

        // Flip above the anchor when there is not enough room below.
        if (y + height > visible.bottom - margin) {
            int above = anchorRect.top - height - margin;
            if (above > visible.top + margin) {
                y = above;
            } else {
                y = Math.max(visible.top + margin, visible.bottom - height - margin);
            }
        }
        // Keep horizontally inside the window.
        if (x + width > visible.right - margin) x = visible.right - width - margin;
        if (x < visible.left + margin) x = visible.left + margin;

        window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    // ---------------------------------------------------------------- content

    private View buildContent() {
        int surface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        int elevated = Ui.blend(surface,
                Ui.isLight(surface) ? 0xFF000000 : 0xFFFFFFFF, 0.04f);
        int outline = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0x1F000000);
        int radius = Ui.dp(context, 14);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (!horizontal) card.setMinimumWidth(Ui.dp(context, 200));
        card.setBackground(Ui.roundRect(elevated, radius, outline, Ui.dp(context, 1)));
        card.setClipToOutline(true);
        int padV = horizontal ? 4 : 6;
        Ui.setPaddingDp(card, horizontal ? 4 : 0, padV, horizontal ? 4 : 0, padV);

        if (title != null && !horizontal) {
            card.addView(buildTitle());
        }
        for (Item item : items) {
            if (item.id == DIVIDER_ID) {
                card.addView(buildDivider(outline));
            } else if (horizontal) {
                card.addView(buildIcon(item));
            } else {
                card.addView(buildRow(item));
            }
        }

        // A scroll container hands its child MATCH_PARENT by default, which under a
        // wrap-content measurement expands the card to the whole screen width. The
        // card must be explicitly told to hug its content.
        ViewGroup.LayoutParams hug = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        if (horizontal) {
            HorizontalScrollView scroller = new HorizontalScrollView(context);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.addView(card, hug);
            return scroller;
        }
        // Long menus scroll rather than run off the screen.
        ScrollView scroller = new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int maxHeight = Ui.dp(getContext(), 420);
                super.onMeasure(widthSpec,
                        View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
            }
        };
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(card, hug);
        return scroller;
    }

    private View buildTitle() {
        TextView view = new TextView(context);
        view.setText(title);
        view.setTextSize(11f);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        view.setTextColor(Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575));
        Ui.setPaddingDp(view, 16, 8, 16, 6);
        return view;
    }

    private View buildDivider(int color) {
        View view = new View(context);
        if (horizontal) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(context, 1), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6));
            view.setLayoutParams(lp);
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1));
            lp.setMargins(0, Ui.dp(context, 5), 0, Ui.dp(context, 5));
            view.setLayoutParams(lp);
        }
        view.setBackgroundColor(color);
        return view;
    }

    private View buildRow(final Item item) {
        int onSurface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        int error = Ui.themeColor(context,
                com.google.android.material.R.attr.colorError, 0xFFD32F2F);
        int labelColor = item.destructive ? error : onSurface;
        if (!item.enabled) labelColor = Ui.withAlpha(labelColor, 0.38f);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(context, 44));
        Ui.setPaddingDp(row, 14, 4, 14, 4);
        row.setBackground(Ui.ripple(Ui.withAlpha(onSurface, 0.10f), null));

        if (item.iconRes != 0) {
            ImageView icon = new ImageView(context);
            icon.setImageResource(item.iconRes);
            icon.setColorFilter(item.destructive ? error : variant);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(context, 20), Ui.dp(context, 20));
            lp.rightMargin = Ui.dp(context, 14);
            icon.setLayoutParams(lp);
            icon.setAlpha(item.enabled ? 1f : 0.38f);
            row.addView(icon);
        }

        TextView label = new TextView(context);
        label.setText(item.label);
        label.setTextSize(14.5f);
        label.setTextColor(labelColor);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Deliberately not a weighted child: a LinearLayout measured at
        // WRAP_CONTENT hands all remaining space to weighted children, which made
        // the popup stretch to the full screen width instead of hugging its text.
        label.setMaxWidth(Ui.dp(context, 260));
        label.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(label);

        if (item.hint != null) {
            TextView hint = new TextView(context);
            hint.setText(item.hint);
            hint.setTextSize(12f);
            hint.setTextColor(Ui.withAlpha(variant, 0.9f));
            hint.setSingleLine(true);
            hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
            hint.setMaxWidth(Ui.dp(context, 200));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(context, 16);
            hint.setLayoutParams(lp);
            row.addView(hint);
        }

        if (item.checked) {
            ImageView check = new ImageView(context);
            check.setImageResource(com.ccs.shard.R.drawable.ic_check);
            check.setColorFilter(Ui.themeColor(context,
                    com.google.android.material.R.attr.colorPrimary, 0xFF2196F3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(context, 18), Ui.dp(context, 18));
            lp.leftMargin = Ui.dp(context, 12);
            check.setLayoutParams(lp);
            row.addView(check);
        }

        if (item.enabled) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.hapticTap(v);
                    dismiss();
                    if (listener != null) listener.onItem(item.id);
                }
            });
        }
        return row;
    }

    private View buildIcon(final Item item) {
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);
        int primary = Ui.themeColor(context,
                com.google.android.material.R.attr.colorPrimary, 0xFF2196F3);

        View view;
        if (item.iconRes != 0) {
            ImageView icon = new ImageView(context);
            icon.setImageResource(item.iconRes);
            icon.setColorFilter(item.checked ? primary : variant);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            Ui.setPaddingDp(icon, 10, 10, 10, 10);
            view = icon;
        } else {
            TextView label = new TextView(context);
            label.setText(item.label);
            label.setTextSize(14f);
            label.setTextColor(item.checked ? primary : variant);
            label.setGravity(Gravity.CENTER);
            Ui.setPaddingDp(label, 12, 10, 12, 10);
            view = label;
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(context, 44));
        lp.setMargins(Ui.dp(context, 1), 0, Ui.dp(context, 1), 0);
        view.setLayoutParams(lp);
        view.setMinimumWidth(Ui.dp(context, 44));
        view.setContentDescription(item.label);
        Drawable background = Ui.rippleRect(context,
                Ui.withAlpha(variant, 0.12f), Ui.dp(context, 10), 0x00000000);
        view.setBackground(background);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticTap(v);
                dismiss();
                if (listener != null) listener.onItem(item.id);
            }
        });
        return view;
    }
}
