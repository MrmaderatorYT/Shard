package com.ccs.shard;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.Io;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.NoteProperties;
import com.ccs.shard.ui.Ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Filtered note collections backed by portable YAML property key/value pairs. */
public final class CollectionsActivity extends BaseActivity {

    private static final class Record {
        final Note note;
        final Map<String, String> properties;
        Record(Note note, Map<String, String> properties) {
            this.note = note;
            this.properties = properties;
        }
    }

    private EditText filter;
    private LinearLayout rows;
    private ProgressBar progress;
    private List<Record> records = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildShell();
        load();
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
        title.setText(R.string.collections_title);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toolbar);

        filter = new EditText(this);
        filter.setHint(R.string.collections_filter_hint);
        filter.setSingleLine(true);
        filter.setBackgroundResource(R.drawable.bg_search_field);
        filter.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_filter, 0, 0, 0);
        filter.setCompoundDrawablePadding(Ui.dp(this, 9));
        Ui.setPaddingDp(filter, 14, 0, 14, 0);
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        filterLp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        root.addView(filter, filterLp);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { render(); }
        });

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32));
        progressLp.gravity = Gravity.CENTER;
        progressLp.topMargin = Ui.dp(this, 32);
        root.addView(progress, progressLp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        Ui.setPaddingDp(rows, 12, 4, 12, 40);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        Io.load(() -> {
            List<Record> found = new ArrayList<>();
            for (Note meta : repo.notes()) {
                try {
                    Note loaded = repo.loadNoteSync(meta.getId());
                    Map<String, String> properties = NoteProperties.read(loaded);
                    if (!properties.isEmpty()) found.add(new Record(meta, properties));
                } catch (Throwable ignored) {}
            }
            return found;
        }, new Io.Ok<List<Record>>() {
            @Override public void onReady(List<Record> found) {
                progress.setVisibility(View.GONE);
                records = found == null ? new ArrayList<>() : found;
                render();
            }
        });
    }

    private void render() {
        if (rows == null) return;
        rows.removeAllViews();
        String query = filter.getText().toString().trim();
        if (query.isEmpty()) {
            renderPropertyValues();
            return;
        }
        String key = query;
        String value = "";
        int colon = query.indexOf(':');
        if (colon >= 0) {
            key = query.substring(0, colon).trim();
            value = query.substring(colon + 1).trim();
        }
        int count = 0;
        for (Record record : records) {
            if (!matches(record, key, value)) continue;
            rows.addView(noteRow(record));
            count++;
        }
        if (count == 0) addEmpty(R.string.collections_no_matches);
    }

    private boolean matches(Record record, String keyNeedle, String valueNeedle) {
        String k = keyNeedle.toLowerCase(Locale.ROOT);
        String v = valueNeedle.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : record.properties.entrySet()) {
            if (!entry.getKey().toLowerCase(Locale.ROOT).contains(k)) continue;
            if (v.isEmpty() || entry.getValue().toLowerCase(Locale.ROOT).contains(v)) return true;
        }
        return false;
    }

    private void renderPropertyValues() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Record record : records) {
            for (Map.Entry<String, String> entry : record.properties.entrySet()) {
                String pair = entry.getKey() + ":" + entry.getValue();
                Integer count = counts.get(pair);
                counts.put(pair, count == null ? 1 : count + 1);
            }
        }
        if (counts.isEmpty()) {
            addEmpty(R.string.collections_empty);
            return;
        }
        TextView hint = new TextView(this);
        hint.setText(R.string.collections_pick_hint);
        hint.setTextSize(13f);
        hint.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        Ui.setPaddingDp(hint, 8, 8, 8, 12);
        rows.addView(hint);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(this, 48));
            row.setBackground(Ui.rippleRect(this, Ui.withAlpha(Ui.themeColor(this,
                    com.google.android.material.R.attr.colorOnSurface), 0.10f),
                    Ui.dp(this, 10), 0x00000000));
            Ui.setPaddingDp(row, 13, 5, 13, 5);
            TextView pair = new TextView(this);
            pair.setText(entry.getKey());
            pair.setTypeface(Typeface.MONOSPACE);
            pair.setTextSize(14f);
            pair.setTextColor(Ui.themeColor(this,
                    com.google.android.material.R.attr.colorOnSurface));
            row.addView(pair, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView count = new TextView(this);
            count.setText(String.valueOf(entry.getValue()));
            count.setTextColor(Ui.themeColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            row.addView(count);
            row.setOnClickListener(v -> filter.setText(entry.getKey()));
            rows.addView(row);
        }
    }

    private View noteRow(Record record) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 58));
        row.setBackground(Ui.rippleRect(this, Ui.withAlpha(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface), 0.10f),
                Ui.dp(this, 11), 0x00000000));
        Ui.setPaddingDp(row, 13, 8, 13, 8);
        TextView title = new TextView(this);
        title.setText(record.note.getTitle());
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        row.addView(title);
        TextView properties = new TextView(this);
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : record.properties.entrySet()) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append(entry.getKey()).append(':').append(entry.getValue());
        }
        properties.setText(summary);
        properties.setSingleLine(true);
        properties.setEllipsize(android.text.TextUtils.TruncateAt.END);
        properties.setTextSize(11.5f);
        properties.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.addView(properties);
        row.setOnClickListener(v -> NoteEditorActivity.open(this, record.note.getId()));
        return row;
    }

    private void addEmpty(int textRes) {
        TextView empty = new TextView(this);
        empty.setText(textRes);
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(14f);
        empty.setTextColor(Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        Ui.setPaddingDp(empty, 24, 56, 24, 24);
        rows.addView(empty);
    }
}
