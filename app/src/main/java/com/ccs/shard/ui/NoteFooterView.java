package com.ccs.shard.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.shard.R;
import com.ccs.shard.core.Note;

import java.util.List;

/**
 * What sits under a note's content: an empty area that appends a block when
 * tapped, then the note's connections.
 *
 * <p>Backlinks and unresolved links are shown inline rather than behind a menu.
 * In a linked-notes app they are the payoff of linking at all, and burying them
 * is why most mobile implementations feel like a plain notepad. An unresolved
 * link is offered as a one-tap "create this note", which is how a vault grows.
 */
public final class NoteFooterView extends LinearLayout {

    public interface Listener {
        void onAppendBlock();
        void onOpenNote(Note note);
        void onCreateNote(String title);
    }

    private final View tapToAdd;
    private final LinearLayout backlinksSection;
    private final LinearLayout unresolvedSection;

    private Listener listener;

    public NoteFooterView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        tapToAdd = new View(context);
        tapToAdd.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 120)));
        tapToAdd.setOnClickListener(v -> {
            if (listener != null) listener.onAppendBlock();
        });
        addView(tapToAdd);

        backlinksSection = new LinearLayout(context);
        backlinksSection.setOrientation(VERTICAL);
        backlinksSection.setVisibility(GONE);
        addView(backlinksSection);

        unresolvedSection = new LinearLayout(context);
        unresolvedSection.setOrientation(VERTICAL);
        unresolvedSection.setVisibility(GONE);
        addView(unresolvedSection);

        // Leaves room for the formatting bar so the last block is never covered.
        View bottomSpace = new View(context);
        bottomSpace.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 96)));
        addView(bottomSpace);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void bind(List<Note> backlinks, List<String> unresolved) {
        buildBacklinks(backlinks);
        buildUnresolved(unresolved);
    }

    private void buildBacklinks(List<Note> backlinks) {
        backlinksSection.removeAllViews();
        if (backlinks.isEmpty()) {
            backlinksSection.setVisibility(GONE);
            return;
        }
        backlinksSection.setVisibility(VISIBLE);
        backlinksSection.addView(divider());
        backlinksSection.addView(sectionTitle(getContext().getResources()
                .getQuantityString(R.plurals.backlinks_count,
                        backlinks.size(), backlinks.size())));
        for (final Note note : backlinks) {
            backlinksSection.addView(linkRow(
                    note.getEmoji().isEmpty() ? null : note.getEmoji(),
                    note.getTitle(),
                    note.getExcerpt(),
                    R.drawable.ic_backlink,
                    v -> {
                        if (listener != null) listener.onOpenNote(note);
                    }));
        }
    }

    private void buildUnresolved(List<String> unresolved) {
        unresolvedSection.removeAllViews();
        if (unresolved.isEmpty()) {
            unresolvedSection.setVisibility(GONE);
            return;
        }
        unresolvedSection.setVisibility(VISIBLE);
        unresolvedSection.addView(divider());
        unresolvedSection.addView(sectionTitle(
                getContext().getString(R.string.unresolved_links)));
        for (final String target : unresolved) {
            unresolvedSection.addView(linkRow(null, target,
                    getContext().getString(R.string.tap_to_create),
                    R.drawable.ic_add,
                    v -> {
                        if (listener != null) listener.onCreateNote(target);
                    }));
        }
    }

    private View divider() {
        View line = new View(getContext());
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 1));
        lp.leftMargin = Ui.dp(getContext(), 20);
        lp.rightMargin = Ui.dp(getContext(), 20);
        lp.topMargin = Ui.dp(getContext(), 4);
        lp.bottomMargin = Ui.dp(getContext(), 12);
        line.setLayoutParams(lp);
        line.setBackgroundColor(Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOutlineVariant, 0x22808080));
        return line;
    }

    private View sectionTitle(CharSequence text) {
        TextView title = new TextView(getContext());
        title.setText(text);
        title.setTextSize(11f);
        title.setAllCaps(true);
        title.setLetterSpacing(0.06f);
        title.setTextColor(Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF787066));
        Ui.setPaddingDp(title, 20, 0, 20, 8);
        return title;
    }

    private View linkRow(String emoji, CharSequence title, CharSequence subtitle,
                         int iconRes, OnClickListener onClick) {
        int onSurface = Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);
        int variant = Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF787066);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.setPaddingDp(row, 20, 8, 20, 8);
        row.setBackground(Ui.ripple(Ui.withAlpha(onSurface, 0.08f), null));
        row.setOnClickListener(onClick);

        if (emoji != null) {
            TextView icon = new TextView(getContext());
            icon.setText(emoji);
            icon.setTextSize(16f);
            LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 26),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            icon.setLayoutParams(lp);
            row.addView(icon);
        } else {
            ImageView icon = new ImageView(getContext());
            icon.setImageResource(iconRes);
            icon.setColorFilter(variant);
            LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 18), Ui.dp(getContext(), 18));
            lp.rightMargin = Ui.dp(getContext(), 8);
            icon.setLayoutParams(lp);
            row.addView(icon);
        }

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        texts.setLayoutParams(new LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextSize(14f);
        titleView.setTextColor(onSurface);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(titleView);

        if (subtitle != null && subtitle.length() > 0) {
            TextView subtitleView = new TextView(getContext());
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(11.5f);
            subtitleView.setTextColor(variant);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(subtitleView);
        }
        row.addView(texts);
        return row;
    }
}
