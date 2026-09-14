package com.ccs.shard.editor;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;

import com.ccs.shard.core.TexAutocomplete;
import com.ccs.shard.core.TexIndentation;
import com.ccs.shard.core.TexLinter;
import com.ccs.shard.core.TexPairMatcher;
import com.ccs.shard.editor.codeHighliter.HighlightTheme;
import com.ccs.shard.editor.codeHighliter.Tex;
import com.ccs.shard.ui.InlineSuggestionPopup;
import com.ccs.shard.ui.TexEditText;
import com.ccs.shard.ui.Ui;
import com.ccs.shard.ui.WavyUnderlineSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns interactive TeX editing behaviour: completion, indentation, syntax
 * highlighting and delimiter matching. Persistence remains the Activity's job.
 */
public final class TexEditingController {

    private static final int HIGHLIGHT_LIMIT = 30000;
    private static final long HIGHLIGHT_DELAY_MS = 180L;
    private static final long LINT_DELAY_MS = 600L;

    public interface Listener {
        void onTextEdited();

        /**
         * Reports what the linter found, so the screen can summarise it.
         *
         * @param problems newest first is not guaranteed; the first is the one to show
         */
        void onProblemsFound(java.util.List<TexLinter.Problem> problems);
    }

    private final Context context;
    private final TexEditText source;
    private final Listener listener;
    private final InlineSuggestionPopup suggestions;
    private final Runnable syntaxHighlight = new Runnable() {
        @Override public void run() { applySyntaxHighlight(); }
    };
    /**
     * Linting is slower than colouring and much less urgent — nobody wants a
     * squiggle appearing under a brace they are still in the middle of typing —
     * so it runs on a longer, separate debounce.
     */
    private final Runnable lintPass = new Runnable() {
        @Override public void run() { applyLint(); }
    };
    private List<TexLinter.Problem> problems = Collections.emptyList();

    private List<TexAutocomplete.Match> visibleSuggestions = new ArrayList<>();
    private boolean binding;
    private boolean smartEdit;
    private int editStart;
    private int editBefore;
    private int editCount;

    public TexEditingController(Context context, TexEditText source, Listener listener) {
        this.context = context;
        this.source = source;
        this.listener = listener;
        suggestions = new InlineSuggestionPopup(context);
        attach();
    }

    private void attach() {
        source.setOnClickListener(v -> updateSuggestions());
        source.setOnFocusChangeListener((v, focused) -> {
            if (focused) updateSuggestions();
            else suggestions.dismiss();
        });
        source.setOnSelectionChangedListener((start, end) -> {
            updatePairHighlight();
            updateSuggestions();
        });
        source.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_TAB || event.isCtrlPressed()) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) handleTab(event.isShiftPressed());
            return true;
        });
        source.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                editStart = start;
                editBefore = before;
                editCount = count;
            }

            @Override public void afterTextChanged(Editable editable) {
                if (binding || smartEdit) return;
                int changedStart = editStart;
                int changedBefore = editBefore;
                int changedCount = editCount;
                maybeReplaceTypedTab(editable, changedStart, changedBefore, changedCount);
                maybeIndentNewline(editable, changedStart, changedBefore, changedCount);
                maybeCloseEnvironment(editable, changedStart, changedBefore, changedCount);
                maybeDedentClosingLine(editable, changedStart, changedBefore, changedCount);
                notifyEdited();
            }
        });
    }

    public void setText(CharSequence text) {
        binding = true;
        try {
            source.setText(text == null ? "" : text);
            source.setSelection(0);
        } finally {
            binding = false;
        }
        scheduleSyntaxHighlight();
    }

    public void indent() { applyLineIndent(false); }
    public void outdent() { applyLineIndent(true); }
    public boolean isSuggestionShowing() { return suggestions.isShowing(); }
    public void dismissSuggestions() { suggestions.dismiss(); }

    public void start() { scheduleSyntaxHighlight(); }

    public void stop() {
        suggestions.dismiss();
        source.removeCallbacks(syntaxHighlight);
        source.removeCallbacks(lintPass);
    }

    private void notifyEdited() {
        if (listener != null) listener.onTextEdited();
        scheduleSyntaxHighlight();
        source.post(() -> {
            updateSuggestions();
            updatePairHighlight();
        });
    }

    private void updateSuggestions() {
        if (binding || !source.hasFocus()) {
            suggestions.dismiss();
            return;
        }
        int cursor = source.getSelectionStart();
        visibleSuggestions = TexAutocomplete.suggest(source.getText(), cursor, 7);
        if (visibleSuggestions.isEmpty()) {
            suggestions.dismiss();
            return;
        }
        List<String> labels = new ArrayList<>(visibleSuggestions.size());
        for (TexAutocomplete.Match match : visibleSuggestions) labels.add(match.label);
        suggestions.show(source, caretRectOnScreen(), labels, null, this::applySuggestion);
    }

    private void applySuggestion(int position) {
        if (position < 0 || position >= visibleSuggestions.size()) return;
        TexAutocomplete.Match match = visibleSuggestions.get(position);
        Editable editable = source.getText();
        int end = source.getSelectionStart();
        if (editable == null || end < match.replaceStart || end > editable.length()) return;
        smartEdit = true;
        try {
            editable.replace(match.replaceStart, end, match.insertion);
            int caret = Math.min(editable.length(),
                    match.replaceStart + match.caretInInsertion);
            source.setSelection(caret);
            if (match.insertion.startsWith("\\end{")) applyClosingLineDedent(editable, caret);
        } finally {
            smartEdit = false;
        }
        suggestions.dismiss();
        notifyEdited();
    }

    private void maybeReplaceTypedTab(Editable editable, int start, int before, int count) {
        if (before != 0 || count != 1 || start < 0 || start >= editable.length()
                || editable.charAt(start) != '\t') return;
        String spaces = TexIndentation.tabSpaces(editable, start);
        runSmartEdit(() -> {
            editable.replace(start, start + 1, spaces);
            source.setSelection(start + spaces.length());
        });
    }

    private void maybeCloseEnvironment(Editable editable, int start, int before, int count) {
        if (before != 0 || count != 1 || start < 0 || start >= editable.length()
                || editable.charAt(start) != '}') return;
        int cursor = start + 1;
        if (source.getSelectionStart() != cursor || source.getSelectionEnd() != cursor) return;
        TexAutocomplete.EnvironmentExpansion expansion =
                TexAutocomplete.environmentExpansion(editable, cursor);
        if (expansion == null) return;
        runSmartEdit(() -> {
            editable.insert(cursor, expansion.insertion);
            source.setSelection(Math.min(editable.length(),
                    cursor + expansion.caretInInsertion));
        });
    }

    private void maybeIndentNewline(Editable editable, int start, int before, int count) {
        if (before != 0 || count != 1 || start < 0 || start >= editable.length()
                || editable.charAt(start) != '\n') return;
        int cursor = start + 1;
        if (source.getSelectionStart() != cursor || source.getSelectionEnd() != cursor) return;
        TexIndentation.Expansion expansion = TexIndentation.newlineExpansion(editable, cursor);
        if (expansion == null || expansion.insertion.isEmpty()) return;
        runSmartEdit(() -> {
            editable.insert(cursor, expansion.insertion);
            source.setSelection(Math.min(editable.length(),
                    cursor + expansion.caretInInsertion));
        });
    }

    private void maybeDedentClosingLine(Editable editable, int start, int before, int count) {
        if (before != 0 || count != 1 || start < 0 || start >= editable.length()) return;
        char typed = editable.charAt(start);
        if (typed == '}' || typed == ']' || typed == ')') {
            applyClosingLineDedent(editable, source.getSelectionStart());
        }
    }

    private void applyClosingLineDedent(Editable editable, int cursor) {
        TexIndentation.LineEdit edit = TexIndentation.closingLineEdit(editable, cursor);
        if (edit == null) return;
        List<TexIndentation.LineEdit> edits = Collections.singletonList(edit);
        int adjusted = TexIndentation.adjustedOffset(cursor, edits);
        runSmartEdit(() -> {
            editable.replace(edit.start, edit.start + edit.deleteCount, edit.insertion);
            source.setSelection(Math.max(0, Math.min(adjusted, editable.length())));
        });
    }

    private void handleTab(boolean outdent) {
        Editable editable = source.getText();
        if (editable == null) return;
        int start = Math.max(0, Math.min(source.getSelectionStart(), source.getSelectionEnd()));
        int end = Math.max(source.getSelectionStart(), source.getSelectionEnd());
        if (!outdent && start == end) {
            String spaces = TexIndentation.tabSpaces(editable, start);
            runSmartEdit(() -> {
                editable.insert(start, spaces);
                source.setSelection(start + spaces.length());
            });
            notifyEdited();
            return;
        }
        applyLineIndent(outdent);
    }

    private void applyLineIndent(boolean outdent) {
        Editable editable = source.getText();
        if (editable == null) return;
        int rawStart = source.getSelectionStart();
        int rawEnd = source.getSelectionEnd();
        int start = Math.max(0, Math.min(rawStart, rawEnd));
        int end = Math.max(rawStart, rawEnd);
        List<TexIndentation.LineEdit> edits =
                TexIndentation.lineEdits(editable, start, end, outdent);
        if (edits.isEmpty()) return;
        int adjustedStart = TexIndentation.adjustedOffset(start, edits);
        int adjustedEnd = TexIndentation.adjustedOffset(end, edits);
        if (start == end && !outdent && adjustedStart == start
                && edits.get(0).start == start) {
            adjustedStart += TexIndentation.WIDTH;
            adjustedEnd = adjustedStart;
        }
        final int selectionStart = adjustedStart;
        final int selectionEnd = adjustedEnd;
        runSmartEdit(() -> {
            source.beginBatchEdit();
            try {
                for (int i = edits.size() - 1; i >= 0; i--) {
                    TexIndentation.LineEdit edit = edits.get(i);
                    editable.replace(edit.start, edit.start + edit.deleteCount, edit.insertion);
                }
                source.setSelection(clamp(selectionStart, editable.length()),
                        clamp(selectionEnd, editable.length()));
            } finally {
                source.endBatchEdit();
            }
        });
        notifyEdited();
    }

    private void runSmartEdit(Runnable edit) {
        boolean previous = smartEdit;
        smartEdit = true;
        try {
            edit.run();
        } finally {
            smartEdit = previous;
        }
    }

    private void scheduleSyntaxHighlight() {
        source.removeCallbacks(syntaxHighlight);
        source.postDelayed(syntaxHighlight, HIGHLIGHT_DELAY_MS);
        source.removeCallbacks(lintPass);
        source.postDelayed(lintPass, LINT_DELAY_MS);
    }

    /** Problems from the most recent lint pass. */
    public List<TexLinter.Problem> problems() {
        return problems;
    }

    /**
     * Marks structural mistakes with a red squiggle.
     *
     * <p>Only the ranges change; the text is never touched, so linting can never
     * interfere with what is being typed.
     */
    private void applyLint() {
        Editable editable = source.getText();
        if (editable == null) return;
        clearLintSpans(editable);
        if (editable.length() == 0 || editable.length() > HIGHLIGHT_LIMIT) {
            publishProblems(Collections.<TexLinter.Problem>emptyList());
            return;
        }
        List<TexLinter.Problem> found;
        try {
            found = TexLinter.lint(editable);
        } catch (Throwable t) {
            // Linting is advisory; a failure must never block editing.
            publishProblems(Collections.<TexLinter.Problem>emptyList());
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int error = Ui.themeColor(context,
                com.google.android.material.R.attr.colorError, 0xFFD64545);
        for (TexLinter.Problem problem : found) {
            if (!validSpan(problem.start, problem.end, editable.length())) continue;
            editable.setSpan(new WavyUnderlineSpan(error, density),
                    problem.start, problem.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        publishProblems(found);
    }

    private void publishProblems(List<TexLinter.Problem> found) {
        problems = found;
        if (listener != null) listener.onProblemsFound(found);
    }

    private void clearLintSpans(Editable editable) {
        WavyUnderlineSpan[] spans = editable.getSpans(
                0, editable.length(), WavyUnderlineSpan.class);
        for (WavyUnderlineSpan span : spans) editable.removeSpan(span);
    }

    private void applySyntaxHighlight() {
        Editable editable = source.getText();
        if (editable == null) return;
        clearSyntaxSpans(editable);
        if (editable.length() == 0 || editable.length() > HIGHLIGHT_LIMIT) return;
        try {
            Tex highlighter = new Tex();
            boolean light = Ui.isLight(Ui.themeColor(context,
                    com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF));
            highlighter.setTheme(light ? HighlightTheme.NOTION_LIGHT
                    : HighlightTheme.VS_CODE_DARK);
            SpannableStringBuilder highlighted = highlighter.highlight(editable.toString());
            copySyntaxSpans(highlighted, editable);
        } catch (Throwable ignored) {
            // Highlighting is visual only; source editing must remain available.
        }
        updatePairHighlight();
    }

    private void copySyntaxSpans(SpannableStringBuilder highlighted, Editable editable) {
        ForegroundColorSpan[] colors = highlighted.getSpans(
                0, highlighted.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan color : colors) {
            int start = highlighted.getSpanStart(color);
            int end = highlighted.getSpanEnd(color);
            if (validSpan(start, end, editable.length())) {
                editable.setSpan(new EditorSyntaxColorSpan(color.getForegroundColor()),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        StyleSpan[] styles = highlighted.getSpans(0, highlighted.length(), StyleSpan.class);
        for (StyleSpan style : styles) {
            int start = highlighted.getSpanStart(style);
            int end = highlighted.getSpanEnd(style);
            if (validSpan(start, end, editable.length())) {
                editable.setSpan(new EditorSyntaxStyleSpan(style.getStyle()),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private void clearSyntaxSpans(Editable editable) {
        EditorSyntaxColorSpan[] colors = editable.getSpans(
                0, editable.length(), EditorSyntaxColorSpan.class);
        for (EditorSyntaxColorSpan color : colors) editable.removeSpan(color);
        EditorSyntaxStyleSpan[] styles = editable.getSpans(
                0, editable.length(), EditorSyntaxStyleSpan.class);
        for (EditorSyntaxStyleSpan style : styles) editable.removeSpan(style);
    }

    private void updatePairHighlight() {
        Editable editable = source.getText();
        if (editable == null) return;
        EditorPairSpan[] old = editable.getSpans(0, editable.length(), EditorPairSpan.class);
        for (EditorPairSpan span : old) editable.removeSpan(span);
        int cursor = source.getSelectionStart();
        if (cursor < 0 || cursor != source.getSelectionEnd()) return;
        TexPairMatcher.Pair pair = TexPairMatcher.find(editable, cursor);
        if (pair == null) return;
        int accent = Ui.themeColor(context,
                com.google.android.material.R.attr.colorPrimary, 0xFF6750A4);
        int color = Ui.withAlpha(accent, 0.28f);
        editable.setSpan(new EditorPairSpan(color), pair.firstStart,
                pair.firstStart + pair.firstLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        editable.setSpan(new EditorPairSpan(color), pair.secondStart,
                pair.secondStart + pair.secondLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private Rect caretRectOnScreen() {
        int offset = Math.max(0, source.getSelectionStart());
        Layout layout = source.getLayout();
        int[] location = new int[2];
        source.getLocationOnScreen(location);
        if (layout == null) {
            return new Rect(location[0], location[1], location[0],
                    location[1] + source.getHeight());
        }
        int safeOffset = Math.min(offset, source.length());
        int line = layout.getLineForOffset(safeOffset);
        int x = Math.round(layout.getPrimaryHorizontal(safeOffset));
        int left = location[0] + source.getPaddingLeft() + x - source.getScrollX();
        int top = location[1] + source.getPaddingTop()
                + layout.getLineTop(line) - source.getScrollY();
        int bottom = location[1] + source.getPaddingTop()
                + layout.getLineBottom(line) - source.getScrollY();
        return new Rect(left, top, left, bottom);
    }

    private static boolean validSpan(int start, int end, int length) {
        return start >= 0 && end > start && end <= length;
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(value, length));
    }

    private static final class EditorSyntaxColorSpan extends ForegroundColorSpan {
        EditorSyntaxColorSpan(int color) { super(color); }
    }

    private static final class EditorSyntaxStyleSpan extends StyleSpan {
        EditorSyntaxStyleSpan(int style) { super(style); }
    }

    private static final class EditorPairSpan extends BackgroundColorSpan {
        EditorPairSpan(int color) { super(color); }
    }
}
