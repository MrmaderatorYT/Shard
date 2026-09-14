package com.ccs.shard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteProperties;
import com.ccs.shard.ui.Ui;
import com.google.android.material.button.MaterialButton;

import java.util.Map;

/** Edits portable scalar YAML front-matter properties for one note. */
public final class PropertiesActivity extends BaseActivity {

    private static final String EXTRA_NOTE = "note";
    private LinearLayout rows;
    private Note note;

    public static void start(Context context, String noteId) {
        context.startActivity(new Intent(context, PropertiesActivity.class)
                .putExtra(EXTRA_NOTE, noteId));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildShell();
        String id = getIntent().getStringExtra(EXTRA_NOTE);
        if (id == null) { finish(); return; }
        repo.loadNote(id, new Io.Result<Note>() {
            @Override public void onReady(Note value) { note = value; render(); }
            @Override public void onError(Throwable error) { toast(R.string.note_not_found); finish(); }
        });
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorSurface));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(toolbar, 4, 4, 8, 4);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), null));
        back.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView title = new TextView(this);
        title.setText(R.string.properties_title);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toolbar);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(rows, 16, 12, 16, 30);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void render() {
        rows.removeAllViews();
        TextView hint = new TextView(this);
        hint.setText(R.string.properties_hint);
        hint.setTextSize(13f);
        hint.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        Ui.setPaddingDp(hint, 4, 0, 4, 14);
        rows.addView(hint);
        for (Map.Entry<String, String> entry : NoteProperties.read(note).entrySet()) {
            rows.addView(propertyRow(entry.getKey(), entry.getValue()));
        }
        MaterialButton add = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        add.setText(R.string.properties_add);
        add.setIconResource(R.drawable.ic_add);
        add.setOnClickListener(v -> edit(null, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        rows.addView(add, lp);
    }

    private View propertyRow(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 54));
        row.setBackground(Ui.rippleRect(this, Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f),
                Ui.dp(this, 10), 0x00000000));
        Ui.setPaddingDp(row, 12, 5, 6, 5);
        TextView keyView = new TextView(this);
        keyView.setText(key);
        keyView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        keyView.setTextSize(13f);
        keyView.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorPrimary));
        row.addView(keyView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f));
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(14f);
        valueView.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        row.addView(valueView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f));
        ImageButton remove = new ImageButton(this);
        remove.setImageResource(R.drawable.ic_close);
        remove.setBackground(Ui.ripple(Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f), null));
        remove.setColorFilter(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        remove.setOnClickListener(v -> {
            NoteProperties.remove(note, key);
            save();
        });
        row.addView(remove, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        row.setOnClickListener(v -> edit(key, value));
        return row;
    }

    private void edit(String oldKey, String oldValue) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(form, 20, 4, 20, 0);
        EditText key = new EditText(this);
        key.setHint(R.string.properties_key);
        key.setSingleLine(true);
        key.setText(oldKey == null ? "" : oldKey);
        key.setEnabled(oldKey == null);
        form.addView(key);
        EditText value = new EditText(this);
        value.setHint(R.string.properties_value);
        value.setSingleLine(true);
        value.setText(oldValue == null ? "" : oldValue);
        form.addView(value);
        dialog().setTitle(oldKey == null ? R.string.properties_add : R.string.properties_edit)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, which) -> {
                    if (!NoteProperties.put(note, key.getText().toString(),
                            value.getText().toString())) {
                        toast(R.string.properties_invalid_key);
                        return;
                    }
                    save();
                }).show();
    }

    private void save() {
        repo.save(note, false);
        render();
    }
}
