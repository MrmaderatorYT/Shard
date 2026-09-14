package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown syntax highlighter for the raw editor.
 * Highlights markdown syntax (headings, bold, italics, links, etc.)
 * AND applies language-specific highlighting inside fenced code blocks.
 */
public class Markdown extends Highlighter {

    // Pattern to match fenced code blocks: ```lang\n...\n```
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile(
        "^(`{3,})(\\w*)\\s*\\n([\\s\\S]*?)^\\1\\s*$",
        Pattern.MULTILINE
    );

    // Color for the ``` fences themselves
    private static final int COLOR_FENCE = 0xFF808080;
    // Background for code blocks (subtle tint)
    private static final int COLOR_CODE_BG = 0x15808080;

    private static final Set<String> KEYWORDS = new HashSet<>();
    private static final Set<String> TYPES = new HashSet<>();
    private static final Set<String> CONSTANTS = new HashSet<>();

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return null; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "`[^`]*`|``[\\s\\S]*?``"; }

    @Override
    protected String getAnnotationPattern() { return null; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        String code = sb.toString();

        // 1. Highlight fenced code blocks first (language-specific)
        applyFencedCodeBlocks(sb, code);

        // 2. Markdown syntax highlighting (outside of code blocks is fine — overlapping spans
        //    are handled gracefully by Android's SpannableStringBuilder)
        applyPattern(sb, code, "^#{1,6}\\s+.+$", COLOR_KEYWORD, true);
        applyPattern(sb, code, "\\*\\*[^*]+\\*\\*|__[^_]+__", COLOR_KEYWORD, true);
        applyPattern(sb, code, "\\*[^*]+\\*|_[^_]+_", COLOR_ANNOTATION, true);
        applyPattern(sb, code, "\\[([^\\]]+)\\]\\([^)]+\\)", COLOR_FUNCTION, false);
        applyPattern(sb, code, "https?://\\S+", COLOR_CONSTANT, false);
        applyPattern(sb, code, "^[-*+]\\s+", COLOR_KEYWORD, false);
        applyPattern(sb, code, "^\\d+\\.\\s+", COLOR_KEYWORD, false);
        applyPattern(sb, code, "^>\\s+", COLOR_COMMENT, false);
    }

    /**
     * Find fenced code blocks and apply language-specific syntax highlighting inside them.
     */
    private void applyFencedCodeBlocks(SpannableStringBuilder sb, String text) {
        Matcher matcher = FENCED_CODE_BLOCK.matcher(text);
        while (matcher.find()) {
            String fence = matcher.group(1);       // the ``` backticks
            String lang = matcher.group(2);         // language tag
            String codeContent = matcher.group(3);  // code inside the block

            int blockStart = matcher.start();
            int blockEnd = matcher.end();
            int codeStart = matcher.start(3);
            int codeEnd = matcher.end(3);

            // Color the fence markers (``` lines) in gray
            int fenceOpenEnd = text.indexOf('\n', blockStart);
            if (fenceOpenEnd < 0) fenceOpenEnd = blockEnd;
            sb.setSpan(new ForegroundColorSpan(COLOR_FENCE),
                blockStart, fenceOpenEnd + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Find closing fence line
            int closingFenceStart = codeEnd;
            sb.setSpan(new ForegroundColorSpan(COLOR_FENCE),
                closingFenceStart, blockEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Apply subtle background to the whole block
            sb.setSpan(new BackgroundColorSpan(COLOR_CODE_BG),
                blockStart, blockEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Get language-specific highlighter
            Highlighter highlighter = LanguageDetector.forLanguage(lang);
            if (highlighter == null && codeContent != null) {
                String detected = LanguageDetector.detectFromContent(codeContent);
                highlighter = LanguageDetector.forLanguage(detected);
            }

            // Apply the highlighter's theme
            if (highlighter != null) {
                highlighter.setTheme(this.theme);
            }

            // Apply syntax highlighting to the code content
            if (highlighter != null && codeContent != null && !codeContent.isEmpty()) {
                SpannableStringBuilder highlighted = highlighter.highlight(codeContent);
                Object[] fgSpans = highlighted.getSpans(0, highlighted.length(), ForegroundColorSpan.class);
                for (Object fgSpan : fgSpans) {
                    int spanStart = highlighted.getSpanStart(fgSpan);
                    int spanEnd = highlighted.getSpanEnd(fgSpan);
                    int color = ((ForegroundColorSpan) fgSpan).getForegroundColor();
                    sb.setSpan(new ForegroundColorSpan(color),
                        codeStart + spanStart,
                        codeStart + spanEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }
}
