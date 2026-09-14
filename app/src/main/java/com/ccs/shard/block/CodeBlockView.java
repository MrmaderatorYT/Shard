package com.ccs.shard.block;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.R;
import com.ccs.shard.editor.codeHighliter.HighlightTheme;
import com.ccs.shard.editor.codeHighliter.Highlighter;
import com.ccs.shard.editor.codeHighliter.LanguageDetector;
import com.ccs.shard.ui.AnchoredMenu;
import com.ccs.shard.ui.Ui;

/**
 * A fenced code block: language chip, copy button, and a monospace editor with
 * syntax highlighting.
 *
 * <p>Highlighting is debounced and size-capped. The shared {@link Highlighter}
 * compiles its patterns per call, so running it on every keystroke of a long
 * snippet was visibly janky on a low-end SoC; waiting for a pause in typing
 * makes it free in practice.
 */
public final class CodeBlockView extends LinearLayout {

    /** Above this many characters, highlighting is skipped entirely. */
    private static final int HIGHLIGHT_LIMIT = 20000;
    private static final long HIGHLIGHT_DELAY_MS = 260L;

    public interface Callbacks {
        void onCodeChanged();
        void onMenu(View anchor);
        void onDrag(View anchor);
        void onCopy(String text);
    }

    private static final String[] LANGUAGES = {
            "", "java", "kotlin", "python", "javascript", "typescript", "c", "cpp",
            "csharp", "go", "rust", "swift", "php", "ruby", "bash", "sql", "json",
            "yaml", "xml", "html", "css", "markdown", "latex", "dart", "lua", "r", "scala"
    };

    private final TextView languageChip;
    private final EditText codeInput;
    private final Runnable highlightTask;

    private Block block;
    private Callbacks callbacks;
    private boolean binding;

    public CodeBlockView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        int surface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        boolean light = Ui.isLight(surface);
        int background = Ui.blend(surface, light ? 0xFF000000 : 0xFFFFFFFF, light ? 0.05f : 0.08f);
        int outline = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0x33808080);
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);

        setBackground(Ui.roundRect(background, Ui.dp(context, 10), outline, Ui.dp(context, 1)));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(header, 10, 5, 4, 0);

        languageChip = new TextView(context);
        languageChip.setTextSize(11.5f);
        languageChip.setTextColor(variant);
        languageChip.setAllCaps(true);
        languageChip.setLetterSpacing(0.05f);
        Ui.setPaddingDp(languageChip, 8, 4, 8, 4);
        languageChip.setBackground(Ui.rippleRect(context, Ui.withAlpha(variant, 0.18f),
                Ui.dp(context, 6), Ui.withAlpha(variant, 0.10f)));
        languageChip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { showLanguagePicker(v); }
        });
        header.addView(languageChip);

        header.addView(new View(context), new LayoutParams(0, 1, 1f));

        ImageView copy = headerButton(R.drawable.ic_copy, R.string.copy_as_markdown, variant);
        copy.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (callbacks != null) callbacks.onCopy(codeInput.getText().toString());
            }
        });
        header.addView(copy);

        ImageView menu = headerButton(R.drawable.ic_menu_dots, R.string.block_options, variant);
        menu.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (callbacks != null) callbacks.onMenu(v);
            }
        });
        menu.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (callbacks != null) callbacks.onDrag(v);
                return true;
            }
        });
        header.addView(menu);

        addView(header, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        codeInput = new EditText(context);
        codeInput.setBackground(null);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(13f);
        codeInput.setTextColor(Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF202124));
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        codeInput.setGravity(Gravity.TOP | Gravity.START);
        codeInput.setHorizontallyScrolling(true);
        codeInput.setHint(R.string.hint_code);
        codeInput.setHintTextColor(Ui.withAlpha(variant, 0.5f));
        Ui.setPaddingDp(codeInput, 12, 6, 12, 12);
        addView(codeInput, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        highlightTask = new Runnable() {
            @Override public void run() { applyHighlight(); }
        };

        codeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (binding || block == null) return;
                block.text = s.toString();
                if (callbacks != null) callbacks.onCodeChanged();
                removeCallbacks(highlightTask);
                postDelayed(highlightTask, HIGHLIGHT_DELAY_MS);
            }
        });
    }

    private ImageView headerButton(int iconRes, int descriptionRes, int tint) {
        ImageView button = new ImageView(getContext());
        button.setImageResource(iconRes);
        button.setColorFilter(tint);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(Ui.rippleRect(getContext(), Ui.withAlpha(tint, 0.18f),
                Ui.dp(getContext(), 8), 0x00000000));
        button.setContentDescription(getContext().getString(descriptionRes));
        LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 30), Ui.dp(getContext(), 30));
        button.setLayoutParams(lp);
        Ui.setPaddingDp(button, 6, 6, 6, 6);
        return button;
    }

    public void bind(Block block, Callbacks callbacks) {
        this.block = block;
        this.callbacks = callbacks;
        binding = true;
        try {
            codeInput.setText(block.text);
            languageChip.setText(block.language == null || block.language.isEmpty()
                    ? getContext().getString(R.string.code_language_plain)
                    : block.language);
        } finally {
            binding = false;
        }
        removeCallbacks(highlightTask);
        post(highlightTask);
    }

    private void applyHighlight() {
        if (block == null) return;
        String code = codeInput.getText().toString();
        if (code.isEmpty() || code.length() > HIGHLIGHT_LIMIT) return;
        try {
            Highlighter highlighter = LanguageDetector.resolve(block.language, code, getContext());
            if (highlighter == null) return;
            boolean light = Ui.isLight(Ui.themeColor(getContext(),
                    com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF));
            highlighter.setTheme(light ? HighlightTheme.NOTION_LIGHT : HighlightTheme.VS_CODE_DARK);
            CharSequence highlighted = highlighter.highlight(code);
            int selection = codeInput.getSelectionStart();
            binding = true;
            try {
                codeInput.setText(highlighted);
                codeInput.setSelection(Math.max(0, Math.min(selection, codeInput.length())));
            } finally {
                binding = false;
            }
        } catch (Throwable ignored) {
            // A highlighter failure must never cost the user their code.
        }
    }

    private void showLanguagePicker(View anchor) {
        AnchoredMenu menu = AnchoredMenu.vertical(getContext())
                .title(getContext().getString(R.string.code_language));
        for (int i = 0; i < LANGUAGES.length; i++) {
            String language = LANGUAGES[i];
            String label = language.isEmpty()
                    ? getContext().getString(R.string.code_language_plain) : language;
            menu.add(new AnchoredMenu.Item(i, 0, label)
                    .checked(language.equals(block == null ? "" : block.language)));
        }
        menu.onItem(new AnchoredMenu.OnItemClick() {
            @Override public void onItem(int id) {
                if (block == null || id < 0 || id >= LANGUAGES.length) return;
                block.language = LANGUAGES[id];
                languageChip.setText(block.language.isEmpty()
                        ? getContext().getString(R.string.code_language_plain) : block.language);
                if (callbacks != null) callbacks.onCodeChanged();
                removeCallbacks(highlightTask);
                post(highlightTask);
            }
        }).showAt(anchor);
    }
}
