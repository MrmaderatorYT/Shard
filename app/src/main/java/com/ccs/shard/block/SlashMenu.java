package com.ccs.shard.block;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ccs.shard.R;
import com.ccs.shard.ui.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The Notion-style {@code /} palette.
 *
 * <p>Opens beside the caret when the user types {@code /} at the start of an
 * empty block, and filters live as they keep typing. Keyboard focus stays in the
 * editor — the popup is explicitly not focusable — so the soft keyboard never
 * closes and the text the user types keeps flowing into the block while the list
 * narrows above it.
 */
public final class SlashMenu {

    public interface Listener {
        /** A command was chosen. */
        void onCommand(SlashCommand command);
        /** The palette closed without a choice. */
        void onCancelled();
    }

    private final Context context;
    private final Listener listener;
    private final List<SlashCommand> commands = SlashCommand.all();
    private final List<SlashCommand> visible = new ArrayList<>();

    private PopupWindow window;
    private LinearLayout list;
    private TextView emptyLabel;
    private ScrollView scroller;
    private int selected;
    private String query = "";

    public SlashMenu(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public boolean isShowing() {
        return window != null && window.isShowing();
    }

    public String query() { return query; }

    /** Opens the palette next to a caret rectangle in {@code anchor}'s space. */
    public void show(View anchor, Rect caretOnScreen) {
        if (isShowing()) {
            reposition(anchor, caretOnScreen);
            return;
        }
        query = "";
        selected = 0;

        View content = buildContent();
        window = new PopupWindow(content,
                Ui.dp(context, 292),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
        window.setBackgroundDrawable(null);
        window.setOutsideTouchable(true);
        window.setTouchable(true);
        // Not focusable: the editor keeps the caret and the keyboard stays up.
        window.setFocusable(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setElevation(Ui.dp(context, 14));
        }
        window.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override public void onDismiss() {
                window = null;
                if (listener != null) listener.onCancelled();
            }
        });
        applyFilter("");
        int[] position = position(anchor, caretOnScreen, content);
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, position[0], position[1]);
    }

    public void reposition(View anchor, Rect caretOnScreen) {
        if (!isShowing()) return;
        View content = window.getContentView();
        int[] position = position(anchor, caretOnScreen, content);
        window.update(position[0], position[1], -1, -1);
    }

    private int[] position(View anchor, Rect caret, View content) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(Ui.dp(context, 292), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Ui.dp(context, 292);
        int height = content.getMeasuredHeight();

        Rect visibleFrame = new Rect();
        anchor.getRootView().getWindowVisibleDisplayFrame(visibleFrame);
        int margin = Ui.dp(context, 8);

        int x = caret.left;
        int y = caret.bottom + Ui.dp(context, 6);
        if (y + height > visibleFrame.bottom - margin) {
            int above = caret.top - height - Ui.dp(context, 6);
            y = above > visibleFrame.top + margin
                    ? above
                    : Math.max(visibleFrame.top + margin, visibleFrame.bottom - height - margin);
        }
        if (x + width > visibleFrame.right - margin) x = visibleFrame.right - width - margin;
        if (x < visibleFrame.left + margin) x = visibleFrame.left + margin;
        return new int[]{x, y};
    }

    public void dismiss() {
        if (window != null) {
            PopupWindow local = window;
            window = null;
            local.setOnDismissListener(null);
            local.dismiss();
        }
    }

    // ---------------------------------------------------------------- filtering

    /**
     * Updates the visible commands for the text typed after the slash.
     *
     * @return false when nothing matches, so the caller can close the palette
     */
    public boolean setQuery(String value) {
        query = value == null ? "" : value;
        applyFilter(query);
        return !visible.isEmpty();
    }

    private void applyFilter(String q) {
        visible.clear();
        final String needle = q.trim();
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            SlashCommand command = commands.get(i);
            int score = command.score(needle, context.getString(command.titleRes));
            if (score > 0) scored.add(new int[]{score, i});
        }
        Collections.sort(scored, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) return b[0] - a[0];
                return a[1] - b[1];
            }
        });
        for (int[] entry : scored) visible.add(commands.get(entry[1]));
        selected = 0;
        rebuildRows();
    }

    /** Moves the highlight; returns the command under it. */
    public SlashCommand moveSelection(int delta) {
        if (visible.isEmpty()) return null;
        selected = Math.max(0, Math.min(visible.size() - 1, selected + delta));
        rebuildRows();
        return visible.get(selected);
    }

    public SlashCommand selectedCommand() {
        if (visible.isEmpty()) return null;
        return visible.get(Math.min(selected, visible.size() - 1));
    }

    // ---------------------------------------------------------------- views

    private View buildContent() {
        int surface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        int elevated = Ui.blend(surface,
                Ui.isLight(surface) ? 0xFF000000 : 0xFFFFFFFF, 0.05f);
        int outline = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0x1F000000);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundRect(elevated, Ui.dp(context, 14),
                outline, Ui.dp(context, 1)));
        Ui.setPaddingDp(card, 0, 6, 0, 6);

        TextView header = new TextView(context);
        header.setText(R.string.slash_menu_title);
        header.setTextSize(11f);
        header.setAllCaps(true);
        header.setLetterSpacing(0.06f);
        header.setTextColor(Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575));
        Ui.setPaddingDp(header, 16, 6, 16, 6);
        card.addView(header);

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        scroller = new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                        Ui.dp(getContext(), 268), View.MeasureSpec.AT_MOST));
            }
        };
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(list);
        card.addView(scroller);

        emptyLabel = new TextView(context);
        emptyLabel.setText(R.string.slash_menu_empty);
        emptyLabel.setTextSize(13f);
        emptyLabel.setTextColor(Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575));
        Ui.setPaddingDp(emptyLabel, 16, 10, 16, 12);
        emptyLabel.setVisibility(View.GONE);
        card.addView(emptyLabel);

        return card;
    }

    private void rebuildRows() {
        if (list == null) return;
        list.removeAllViews();
        if (visible.isEmpty()) {
            emptyLabel.setVisibility(View.VISIBLE);
            scroller.setVisibility(View.GONE);
            return;
        }
        emptyLabel.setVisibility(View.GONE);
        scroller.setVisibility(View.VISIBLE);
        for (int i = 0; i < visible.size(); i++) {
            list.addView(buildRow(visible.get(i), i == selected));
        }
    }

    private View buildRow(final SlashCommand command, boolean highlighted) {
        int onSurface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        int primary = Ui.themeColor(context,
                com.google.android.material.R.attr.colorPrimary, 0xFF2196F3);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(row, 12, 7, 12, 7);
        int highlight = highlighted ? Ui.withAlpha(primary, 0.12f) : 0x00000000;
        row.setBackground(Ui.rippleRect(context, Ui.withAlpha(onSurface, 0.10f),
                Ui.dp(context, 10), highlight));

        ImageView icon = new ImageView(context);
        icon.setImageResource(command.iconRes);
        icon.setColorFilter(highlighted ? primary : variant);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 34), Ui.dp(context, 34));
        iconParams.rightMargin = Ui.dp(context, 12);
        icon.setLayoutParams(iconParams);
        icon.setBackground(Ui.roundRect(Ui.withAlpha(variant, 0.10f), Ui.dp(context, 8)));
        Ui.setPaddingDp(icon, 7, 7, 7, 7);
        row.addView(icon);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setText(command.titleRes);
        title.setTextSize(14.5f);
        title.setTextColor(onSurface);
        title.setSingleLine(true);
        texts.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText(command.subtitleRes);
        subtitle.setTextSize(11.5f);
        subtitle.setTextColor(variant);
        subtitle.setSingleLine(true);
        texts.addView(subtitle);

        row.addView(texts);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticTap(v);
                dismiss();
                if (listener != null) listener.onCommand(command);
            }
        });
        return row;
    }
}
