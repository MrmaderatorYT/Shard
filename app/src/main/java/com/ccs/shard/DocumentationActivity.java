package com.ccs.shard;

import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.ui.Ui;

/** Quick reference for the Markdown block editor and the TeX source editor. */
public final class DocumentationActivity extends BaseActivity {

    private LinearLayout content;
    private int onSurface;
    private int variant;
    private int primary;
    private int surfaceContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_documentation);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        content = findViewById(R.id.docsContent);
        onSurface = Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124);
        variant = Ui.themeColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF6B7280);
        primary = Ui.themeColor(this,
                com.google.android.material.R.attr.colorPrimary, 0xFF0B57D0);
        surfaceContainer = Ui.themeColor(this,
                com.google.android.material.R.attr.colorSurfaceContainer,
                Ui.blend(Ui.themeColor(this,
                        com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF),
                        onSurface, 0.06f));
        buildDocumentation();
    }

    private void buildDocumentation() {
        addIntro(R.string.docs_intro);

        addSection(R.string.docs_md_title);
        addParagraph(R.string.docs_md_intro);
        addMappingHeader();
        addMappings(R.string.docs_md_mapping);
        addSubsection(R.string.docs_md_shortcuts_title);
        addParagraph(R.string.docs_md_shortcuts);
        addSubsection(R.string.docs_md_slash_title);
        addParagraph(R.string.docs_md_slash);
        addParagraph(R.string.docs_md_notes);

        addSection(R.string.docs_tex_title);
        addParagraph(R.string.docs_tex_intro);
        addSubsection(R.string.docs_tex_structure_title);
        addCode(R.string.docs_tex_structure);
        addMappingHeader();
        addMappings(R.string.docs_tex_mapping);

        addSubsection(R.string.docs_symbols_title);
        addCode(R.string.docs_tex_symbols);
        addParagraph(R.string.docs_tex_rules);
        addFooter(R.string.docs_footer);
    }

    private void addIntro(@StringRes int textRes) {
        TextView view = text(textRes, 16f, onSurface);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackground(Ui.roundRect(Ui.withAlpha(primary, 0.10f), Ui.dp(this, 12)));
        Ui.setPaddingDp(view, 14, 13, 14, 13);
        add(view, 0, 0, 0, 16);
    }

    private void addSection(@StringRes int textRes) {
        TextView view = text(textRes, 22f, onSurface);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(view, 0, 18, 0, 7);
    }

    private void addSubsection(@StringRes int textRes) {
        TextView view = text(textRes, 15.5f, primary);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(view, 0, 14, 0, 6);
    }

    private void addParagraph(@StringRes int textRes) {
        TextView view = text(textRes, 15f, onSurface);
        view.setLineSpacing(0f, 1.28f);
        add(view, 0, 0, 0, 12);
    }

    private void addFooter(@StringRes int textRes) {
        TextView view = text(textRes, 13.5f, variant);
        view.setLineSpacing(0f, 1.24f);
        view.setBackground(Ui.roundRect(Ui.withAlpha(variant, 0.08f), Ui.dp(this, 10)));
        Ui.setPaddingDp(view, 12, 11, 12, 11);
        add(view, 0, 18, 0, 0);
    }

    private void addCode(@StringRes int textRes) {
        TextView view = text(textRes, 13.5f, onSurface);
        view.setTypeface(Typeface.MONOSPACE);
        view.setLineSpacing(0f, 1.18f);
        view.setBackground(Ui.roundRect(surfaceContainer, Ui.dp(this, 10),
                Ui.withAlpha(variant, 0.22f), Ui.dp(this, 1)));
        Ui.setPaddingDp(view, 13, 11, 13, 11);
        add(view, 0, 0, 0, 12);
    }

    private void addMappingHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView source = text(R.string.docs_source_label, 11f, variant);
        source.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        source.setAllCaps(true);
        TextView display = text(R.string.docs_display_label, 11f, variant);
        display.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        display.setAllCaps(true);
        row.addView(source, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.47f));
        row.addView(display, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.53f));
        add(row, 4, 0, 4, 5);
    }

    /** Each resource line is written as {@code source → displayed result}. */
    private void addMappings(@StringRes int textRes) {
        String[] lines = getString(textRes).split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int separator = trimmed.indexOf("→");
            if (separator < 0) {
                addParagraphText(trimmed);
                continue;
            }
            String source = trimmed.substring(0, separator).trim();
            String result = trimmed.substring(separator + 1).trim();
            addMapping(source, result);
        }
    }

    private void addMapping(String sourceValue, String resultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.roundRect(surfaceContainer, Ui.dp(this, 8)));
        Ui.setPaddingDp(row, 9, 7, 9, 7);

        TextView source = new TextView(this);
        source.setText(sourceValue);
        source.setTextSize(13f);
        source.setTextColor(onSurface);
        source.setTypeface(Typeface.MONOSPACE);
        source.setLineSpacing(0f, 1.12f);

        TextView result = new TextView(this);
        result.setText(resultValue);
        result.setTextSize(13.5f);
        result.setTextColor(variant);
        result.setLineSpacing(0f, 1.12f);

        row.addView(source, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.47f));
        TextView arrow = new TextView(this);
        arrow.setText("→");
        arrow.setTextSize(14f);
        arrow.setTextColor(primary);
        Ui.setPaddingDp(arrow, 5, 0, 5, 0);
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(result, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.53f));
        add(row, 0, 0, 0, 5);
    }

    private void addParagraphText(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(14f);
        view.setTextColor(variant);
        view.setLineSpacing(0f, 1.2f);
        add(view, 0, 0, 0, 7);
    }

    private TextView text(@StringRes int textRes, float size, int color) {
        return text(getString(textRes), size, color);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setSimpleBreakStrategy(view);
            view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        return view;
    }

    /** The framework API existed before its currently annotated LineBreaker constants. */
    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    @android.annotation.SuppressLint("WrongConstant")
    private static void setSimpleBreakStrategy(TextView view) {
        // BREAK_STRATEGY_SIMPLE has always been represented by 0.
        view.setBreakStrategy(0);
    }

    private void add(View view, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(this, left);
        params.topMargin = Ui.dp(this, top);
        params.rightMargin = Ui.dp(this, right);
        params.bottomMargin = Ui.dp(this, bottom);
        content.addView(view, params);
    }
}
