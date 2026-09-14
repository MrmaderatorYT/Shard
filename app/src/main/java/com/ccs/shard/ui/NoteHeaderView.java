package com.ccs.shard.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.R;
import com.ccs.shard.core.Note;

import java.util.List;

/**
 * The top of a note: icon, title, and a line of context.
 *
 * <p>The title is a real text field rather than a toolbar label, because in this
 * app the title <em>is</em> the file name — editing it here renames the file and
 * updates every wiki link that pointed at it. The context line exists so the two
 * facts people actually want (when it was last edited, what links here) are
 * visible without opening a menu.
 */
public final class NoteHeaderView extends LinearLayout {

    public interface Listener {
        void onTitleChanged(String title);
        void onPickIcon();
        void onTagClicked(String tag);
        void onBacklinksClicked();
    }

    private final TextView iconButton;
    private final EditText titleInput;
    private final TextView metaLine;
    private final LinearLayout tagRow;
    private final HorizontalScrollView tagScroller;

    private Listener listener;
    private boolean binding;

    public NoteHeaderView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        Ui.setPaddingDp(this, 20, 18, 20, 6);

        int onSurface = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);
        int variant = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF787066);

        iconButton = new TextView(context);
        iconButton.setTextSize(34f);
        iconButton.setGravity(Gravity.CENTER);
        iconButton.setBackground(Ui.rippleRect(context, Ui.withAlpha(onSurface, 0.10f),
                Ui.dp(context, 10), 0x00000000));
        LayoutParams iconParams = new LayoutParams(Ui.dp(context, 52), Ui.dp(context, 52));
        iconParams.bottomMargin = Ui.dp(context, 2);
        iconButton.setLayoutParams(iconParams);
        iconButton.setContentDescription(context.getString(R.string.note_icon));
        iconButton.setOnClickListener(v -> {
            if (listener != null) listener.onPickIcon();
        });
        addView(iconButton);

        titleInput = new EditText(context);
        titleInput.setBackground(null);
        titleInput.setTextSize(28f);
        titleInput.setTypeface(Typeface.DEFAULT_BOLD);
        titleInput.setTextColor(onSurface);
        titleInput.setHint(R.string.untitled);
        titleInput.setHintTextColor(Ui.withAlpha(variant, 0.45f));
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setSingleLine(true);
        titleInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        titleInput.setPadding(0, 0, 0, Ui.dp(context, 6));
        titleInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (binding || listener == null) return;
                listener.onTitleChanged(s.toString());
            }
        });
        addView(titleInput, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        metaLine = new TextView(context);
        metaLine.setTextSize(12f);
        metaLine.setTextColor(variant);
        metaLine.setPadding(0, 0, 0, Ui.dp(context, 8));
        metaLine.setOnClickListener(v -> {
            if (listener != null) listener.onBacklinksClicked();
        });
        addView(metaLine);

        tagRow = new LinearLayout(context);
        tagRow.setOrientation(HORIZONTAL);

        tagScroller = new HorizontalScrollView(context);
        tagScroller.setHorizontalScrollBarEnabled(false);
        tagScroller.addView(tagRow);
        tagScroller.setPadding(0, 0, 0, Ui.dp(context, 6));
        addView(tagScroller, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public EditText titleInput() { return titleInput; }

    /** Applies a note's identity to the header without re-triggering listeners. */
    public void bind(Note note, int backlinkCount, CharSequence relativeEdited) {
        binding = true;
        try {
            String title = note.getTitle() == null ? "" : note.getTitle();
            if (!titleInput.getText().toString().equals(title)) {
                titleInput.setText(title);
            }
            String emoji = note.getEmoji();
            iconButton.setText(emoji.isEmpty() ? "＋" : emoji);
            iconButton.setAlpha(emoji.isEmpty() ? 0.32f : 1f);
            iconButton.setTextSize(emoji.isEmpty() ? 20f : 34f);

            StringBuilder meta = new StringBuilder();
            meta.append(getContext().getString(R.string.words_count, note.getWordCount()));
            meta.append("  ·  ").append(relativeEdited);
            if (backlinkCount > 0) {
                meta.append("  ·  ").append(getContext().getResources()
                        .getQuantityString(R.plurals.backlinks_count,
                                backlinkCount, backlinkCount));
            }
            metaLine.setText(meta);

            bindTags(note.getTags());
        } finally {
            binding = false;
        }
    }

    private void bindTags(List<String> tags) {
        tagRow.removeAllViews();
        tagScroller.setVisibility(tags.isEmpty() ? GONE : VISIBLE);
        int primary = Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorPrimary, 0xFF2F6FEB);
        for (final String tag : tags) {
            TextView chip = new TextView(getContext());
            chip.setText("#" + tag);
            chip.setTextSize(12f);
            chip.setTextColor(primary);
            Ui.setPaddingDp(chip, 9, 4, 9, 4);
            chip.setBackground(Ui.rippleRect(getContext(), Ui.withAlpha(primary, 0.2f),
                    Ui.dp(getContext(), 8), Ui.withAlpha(primary, 0.10f)));
            LayoutParams lp = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(getContext(), 6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (listener != null) listener.onTagClicked(tag);
            });
            tagRow.addView(chip);
        }
    }
}
