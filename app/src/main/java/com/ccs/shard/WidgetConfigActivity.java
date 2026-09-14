package com.ccs.shard;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.TaskItem;
import com.ccs.shard.core.TaskRepository;
import com.ccs.shard.ui.Ui;

import java.util.List;

/** Picks what the main area of each widget opens. */
public final class WidgetConfigActivity extends BaseActivity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private LinearLayout rows;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorSurface));
        TextView title = new TextView(this);
        title.setText(R.string.widget_config_title);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        Ui.setPaddingDp(title, 22, 24, 22, 12);
        root.addView(title);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(rows, 12, 4, 12, 24);
        root.addView(rows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        option(R.drawable.ic_add, R.string.new_note_widget, "new", null,
                getString(R.string.new_note_widget));
        option(R.drawable.ic_block_date, R.string.nav_daily_note, "daily", null,
                getString(R.string.nav_daily_note));
        option(R.drawable.ic_checkbox, R.string.nav_tasks, "tasks", null,
                getString(R.string.nav_tasks));
        View note = optionView(R.drawable.ic_notes, getString(R.string.widget_specific_note));
        note.setOnClickListener(v -> pickNote());
        rows.addView(note);
        View task = optionView(R.drawable.ic_checkbox, getString(R.string.widget_specific_task));
        task.setOnClickListener(v -> pickTask());
        rows.addView(task);
        setContentView(root);
    }

    private void option(int icon, int title, String mode, String target, String label) {
        View row = optionView(icon, getString(title));
        row.setOnClickListener(v -> save(mode, target, label));
        rows.addView(row);
    }

    private View optionView(int iconRes, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 54));
        row.setBackground(Ui.rippleRect(this, Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f),
                Ui.dp(this, 11), 0x00000000));
        Ui.setPaddingDp(row, 14, 6, 14, 6);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 22));
        iconLp.rightMargin = Ui.dp(this, 16);
        row.addView(icon, iconLp);
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(15f);
        text.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        row.addView(text);
        return row;
    }

    private void pickNote() {
        List<Note> notes = repo.notes();
        java.util.Collections.sort(notes, (a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
        CharSequence[] labels = new CharSequence[notes.size()];
        for (int i = 0; i < notes.size(); i++) labels[i] = notes.get(i).getTitle();
        dialog().setTitle(R.string.widget_specific_note)
                .setItems(labels, (d, which) -> save("note", notes.get(which).getId(),
                        notes.get(which).getTitle()))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void pickTask() {
        Io.load(() -> TaskRepository.scan(repo), new Io.Ok<List<TaskItem>>() {
            @Override public void onReady(List<TaskItem> tasks) {
                java.util.ArrayList<TaskItem> open = new java.util.ArrayList<>();
                for (TaskItem task : tasks) if (!task.checked) open.add(task);
                if (open.isEmpty()) { toast(R.string.tasks_empty); return; }
                CharSequence[] labels = new CharSequence[open.size()];
                for (int i = 0; i < open.size(); i++) {
                    labels[i] = open.get(i).text + " · " + open.get(i).noteTitle;
                }
                dialog().setTitle(R.string.widget_specific_task)
                        .setItems(labels, (d, which) -> save("task",
                                open.get(which).noteId, open.get(which).text))
                        .setNegativeButton(R.string.cancel, null).show();
            }
        });
    }

    private void save(String mode, String target, String label) {
        QuickNoteWidget.configure(this, widgetId, mode, target, label);
        Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        finish();
    }
}
