package com.ccs.shard.editor;

import android.text.Editable;
import android.view.View;

import com.ccs.shard.R;
import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.block.BlockAdapter;
import com.ccs.shard.block.BlockEditText;
import com.ccs.shard.core.Note;
import com.ccs.shard.core.VaultRepository;
import com.ccs.shard.ui.InlineSuggestionPopup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Autocomplete for {@code [[wiki links]]} and {@code #tags} as they are typed.
 *
 * <p>Owns the popup and the six pieces of pending state that go with it — the
 * field being edited, the character range to replace, and the parallel lists of
 * values and "create this" flags. Those lived on the editor activity, where they
 * were six fields that only mattered for a few hundred milliseconds at a time.
 */
public final class InlineSuggestions {

    /** How many candidates are worth offering before the list stops being a shortcut. */
    private static final int MAX_LINK_RESULTS = 7;
    private static final int MAX_TAG_RESULTS = 8;

    private final BaseActivity activity;
    private final VaultRepository repo;
    /** View the popup is positioned against — the block list. */
    private final View anchorHost;

    private InlineSuggestionPopup popup;
    private BlockEditText field;
    private int replaceStart;
    private int replaceEnd;
    private int kind;

    /** The value each row inserts, and whether choosing it also creates a note. */
    private final List<String> values = new ArrayList<>();
    private final List<Boolean> creates = new ArrayList<>();

    public InlineSuggestions(BaseActivity activity, VaultRepository repo, View anchorHost) {
        this.activity = activity;
        this.repo = repo;
        this.anchorHost = anchorHost;
    }

    /**
     * Offers completions for what is being typed.
     *
     * @param currentNote excluded from link results — a note linking to itself is noise
     */
    public void show(BlockEditText source, int suggestionKind, String query,
                     int rangeStart, int rangeEnd, Note currentNote) {
        if (popup == null) popup = new InlineSuggestionPopup(activity);
        this.field = source;
        this.replaceStart = rangeStart;
        this.replaceEnd = rangeEnd;
        this.kind = suggestionKind;
        values.clear();
        creates.clear();

        List<String> labels = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);

        if (suggestionKind == BlockAdapter.INLINE_WIKI_LINK) {
            collectNotes(needle, query, currentNote, labels, hints);
        } else {
            collectTags(needle, query, labels, hints);
        }

        if (labels.isEmpty()) {
            popup.dismiss();
            return;
        }
        popup.show(anchorHost, source.caretRectOnScreen(), labels, hints, this::apply);
    }

    private void collectNotes(String needle, String query, Note currentNote,
                              List<String> labels, List<String> hints) {
        List<Note> candidates = repo.notes();
        // Prefix matches first, then alphabetical: typing "pro" should surface
        // "Project Alpha" before "Reproduction steps".
        java.util.Collections.sort(candidates, (a, b) -> {
            boolean aPrefix = a.getTitle().toLowerCase(Locale.ROOT).startsWith(needle);
            boolean bPrefix = b.getTitle().toLowerCase(Locale.ROOT).startsWith(needle);
            if (aPrefix != bPrefix) return aPrefix ? -1 : 1;
            return a.getTitle().compareToIgnoreCase(b.getTitle());
        });
        boolean exact = repo.index().resolveLink(query) != null;
        for (Note candidate : candidates) {
            if (currentNote != null && candidate.getId().equals(currentNote.getId())) continue;
            String title = candidate.getTitle();
            if (!needle.isEmpty()
                    && !title.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            labels.add(candidate.getEmoji().isEmpty()
                    ? title : candidate.getEmoji() + "  " + title);
            hints.add(candidate.folder().isEmpty()
                    ? activity.getString(R.string.vault_root) : candidate.folder());
            values.add(title);
            creates.add(Boolean.FALSE);
            if (labels.size() >= MAX_LINK_RESULTS) break;
        }
        // Offer to create the target, which is how a vault grows from its own gaps.
        if (!query.trim().isEmpty() && !exact) {
            labels.add(activity.getString(R.string.autocomplete_create_note, query.trim()));
            hints.add(activity.getString(R.string.autocomplete_create_hint));
            values.add(query.trim());
            creates.add(Boolean.TRUE);
        }
    }

    private void collectTags(String needle, String query,
                             List<String> labels, List<String> hints) {
        for (String tag : repo.index().allTags()) {
            if (!needle.isEmpty() && !tag.toLowerCase(Locale.ROOT).contains(needle)) continue;
            labels.add("#" + tag);
            Integer count = repo.index().tagCounts().get(tag);
            hints.add(count == null ? "" : activity.getResources()
                    .getQuantityString(R.plurals.notes_count, count, count));
            values.add(tag);
            creates.add(Boolean.FALSE);
            if (labels.size() >= MAX_TAG_RESULTS) break;
        }
        if (labels.isEmpty() && !query.isEmpty()) {
            labels.add("#" + query);
            hints.add(activity.getString(R.string.autocomplete_new_tag));
            values.add(query);
            creates.add(Boolean.FALSE);
        }
    }

    /** Replaces the typed fragment with the chosen completion. */
    private void apply(int selected) {
        if (field == null || selected < 0 || selected >= values.size()) return;
        String value = values.get(selected);
        boolean create = Boolean.TRUE.equals(creates.get(selected));
        String replacement = kind == BlockAdapter.INLINE_WIKI_LINK
                ? "[[" + value + "]]" : "#" + value + " ";

        Editable editable = field.getText();
        int start = Math.max(0, Math.min(replaceStart, editable.length()));
        int end = Math.max(start, Math.min(replaceEnd, editable.length()));
        editable.replace(start, end, replacement);
        field.setSelection(Math.min(editable.length(), start + replacement.length()));
        popup.dismiss();

        if (create) createTarget(value);
    }

    private void createTarget(String title) {
        if (repo.vault().isSharedStorage() && !repo.vault().hasFullFileAccess()) {
            activity.toast(R.string.storage_locked_title);
            return;
        }
        Note created = repo.createNote(title, "", "");
        activity.toast(activity.getString(R.string.autocomplete_note_created,
                created.getTitle()));
    }

    public void dismiss() {
        if (popup != null) popup.dismiss();
        field = null;
    }
}
