package com.ccs.shard.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ccs.shard.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable, keyboard-friendly list of actions available from the current screen. */
public final class CommandPalette {

    public static final class Action {
        final int icon;
        final String title;
        final String keywords;
        final Runnable runnable;

        public Action(int icon, String title, String keywords, Runnable runnable) {
            this.icon = icon;
            this.title = title;
            this.keywords = keywords == null ? "" : keywords;
            this.runnable = runnable;
        }
    }

    private CommandPalette() {}

    public static void show(Activity activity, List<Action> actions) {
        show(activity, actions, null);
    }

    /** Variant used by intent routers that should close when the palette is cancelled. */
    public static void show(Activity activity, List<Action> actions, Runnable onCancelled) {
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(root, 16, 4, 16, 8);

        final EditText query = new EditText(activity);
        query.setSingleLine(true);
        query.setHint(R.string.command_search_hint);
        query.setTextSize(16f);
        query.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_search, 0, 0, 0);
        query.setCompoundDrawablePadding(Ui.dp(activity, 10));
        root.addView(query, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 52)));

        ScrollView scroll = new ScrollView(activity);
        final LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 360)));

        final Dialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.command_palette)
                .setView(root)
                .setNegativeButton(R.string.cancel, null)
                .create();

        final boolean[] selected = {false};
        final Runnable render = new Runnable() {
            @Override public void run() {
                String needle = query.getText().toString().trim().toLowerCase(Locale.ROOT);
                rows.removeAllViews();
                int shown = 0;
                for (Action action : actions) {
                    String haystack = (action.title + " " + action.keywords)
                            .toLowerCase(Locale.ROOT);
                    if (!needle.isEmpty() && !haystack.contains(needle)) continue;
                    rows.addView(row(activity, action, dialog, selected));
                    shown++;
                }
                if (shown == 0) {
                    TextView empty = new TextView(activity);
                    empty.setText(R.string.command_no_results);
                    empty.setGravity(Gravity.CENTER);
                    empty.setTextColor(Ui.themeColor(activity,
                            com.google.android.material.R.attr.colorOnSurfaceVariant));
                    Ui.setPaddingDp(empty, 12, 28, 12, 28);
                    rows.addView(empty);
                }
            }
        };
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { render.run(); }
        });
        dialog.setOnShowListener(ignored -> {
            render.run();
            query.requestFocus();
            query.postDelayed(() -> {
                InputMethodManager keyboard = (InputMethodManager) activity
                        .getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        });
        dialog.setOnDismissListener(ignored -> {
            if (!selected[0] && onCancelled != null) onCancelled.run();
        });
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private static View row(Activity activity, Action action, Dialog dialog,
                            boolean[] selected) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 50));
        Ui.setPaddingDp(row, 12, 6, 12, 6);
        int onSurface = Ui.themeColor(activity,
                com.google.android.material.R.attr.colorOnSurface);
        int variant = Ui.themeColor(activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        row.setBackground(Ui.rippleRect(activity, Ui.withAlpha(onSurface, 0.10f),
                Ui.dp(activity, 10), 0x00000000));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(action.icon);
        icon.setColorFilter(variant);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(activity, 21), Ui.dp(activity, 21));
        iconLp.rightMargin = Ui.dp(activity, 16);
        row.addView(icon, iconLp);

        TextView title = new TextView(activity);
        title.setText(action.title);
        title.setTextSize(15f);
        title.setTextColor(onSurface);
        title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            selected[0] = true;
            dialog.dismiss();
            action.runnable.run();
        });
        return row;
    }
}
