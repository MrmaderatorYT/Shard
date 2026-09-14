package com.ccs.shard.block;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;

/**
 * Renders inline Markdown to a {@link Spanned}.
 *
 * <p>Two modes, which together give the live-preview behaviour people expect
 * from Obsidian and Notion:
 *
 * <ul>
 *   <li>{@link #render} strips the syntax markers — used for blocks that are not
 *       being edited, so the note reads as formatted prose.</li>
 *   <li>{@link #renderRaw} keeps the markers but dims them — used for the block
 *       that currently has the caret, so the text stays directly editable and
 *       offsets still line up with the model.</li>
 * </ul>
 *
 * <p>Hand-written single pass rather than a regex chain: this runs on every
 * keystroke of the focused block and on every bind of every visible block, and
 * regex backtracking on long lines was measurable on a low-end SoC.
 */
public final class InlineMd {

    /** Limit formatting on massive blocks to prevent UI thread lockups. */
    private static final int MAX_FORMAT_LENGTH = 30_000;
    /** Maximum character distance to search for a matching closing marker. */
    private static final int MAX_SPAN_LOOKAHEAD = 2048;

    /** Colours pulled from the active theme once, then reused for every block. */
    public static final class Palette {
        public int link = 0xFF3B82F6;
        public int linkUnresolved = 0xFFB45309;
        public int tag = 0xFF7C3AED;
        public int code = 0xFFD6336C;
        public int codeBackground = 0x14808080;
        public int marker = 0x66808080;
        public int highlight = 0x40FFD54F;
        public int text = 0xFF202124;
    }

    private final Palette palette;
    /** Resolves a wiki-link target so unresolved links can be shown differently. */
    private LinkResolver resolver;
    /** Resolves document-local GFM references and footnotes. */
    private ReferenceResolver referenceResolver;

    public interface LinkResolver {
        boolean exists(String target);
    }

    public interface ReferenceResolver {
        String referenceTarget(String label);
        boolean hasFootnote(String id);
    }

    public InlineMd(Palette palette) {
        this.palette = palette;
    }

    public void setResolver(LinkResolver resolver) {
        this.resolver = resolver;
    }

    public void setReferenceResolver(ReferenceResolver resolver) {
        this.referenceResolver = resolver;
    }

    public Palette palette() { return palette; }

    /** Formatted text with markers removed. */
    public CharSequence render(String source) {
        return build(source, false);
    }

    /** Formatted text with markers kept but dimmed, for the focused block. */
    public CharSequence renderRaw(String source) {
        return build(source, true);
    }

    // ---------------------------------------------------------------- core

    private CharSequence build(String source, boolean keepMarkers) {
        if (source == null || source.isEmpty()) return "";
        if (source.length() > MAX_FORMAT_LENGTH) return source;

        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        int n = source.length();
        int plainStart = 0;

        while (i < n) {
            char c = source.charAt(i);

            // Escaped character: emit it verbatim.
            if (c == '\\' && i + 1 < n && isMarkerChar(source.charAt(i + 1))) {
                if (i > plainStart) out.append(source, plainStart, i);
                if (keepMarkers) appendMarker(out, "\\");
                out.append(source.charAt(i + 1));
                i += 2;
                plainStart = i;
                continue;
            }

            if (c == '`') {
                int run = runLength(source, i, '`');
                int close = indexOfRun(source, i + run, '`', run);
                if (close > 0) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    String body = source.substring(i + run, close);
                    int start = out.length();
                    if (keepMarkers) appendMarker(out, source.substring(i, i + run));
                    int textStart = out.length();
                    out.append(body);
                    int textEnd = out.length();
                    if (keepMarkers) appendMarker(out, source.substring(i, i + run));
                    apply(out, new TypefaceSpan("monospace"), textStart, textEnd);
                    apply(out, new ForegroundColorSpan(palette.code), textStart, textEnd);
                    apply(out, new BackgroundColorSpan(palette.codeBackground), start, out.length());
                    apply(out, new RelativeSizeSpan(0.94f), textStart, textEnd);
                    i = close + run;
                    plainStart = i;
                    continue;
                }
            }

            if (c == '!' && i + 2 < n && source.charAt(i + 1) == '['
                    && source.charAt(i + 2) == '[') {
                int close = boundedIndexOf(source, "]]", i + 3);
                if (close > i + 3) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    String inner = source.substring(i + 3, close);
                    String target = embedTarget(inner);
                    String resolveTarget = stripHeading(target);
                    String label = embedLabel(inner, target);
                    boolean attachment = isLikelyAttachment(target);
                    boolean exists = attachment || resolver == null || resolver.exists(resolveTarget);
                    if (keepMarkers) appendMarker(out, "![[");
                    int textStart = out.length();
                    out.append(keepMarkers ? inner : (attachment ? "🖼 " : "↳ ") + label);
                    int textEnd = out.length();
                    if (keepMarkers) appendMarker(out, "]]");
                    int color = exists ? palette.link : palette.linkUnresolved;
                    apply(out, new ForegroundColorSpan(color), textStart, textEnd);
                    if (!attachment && !resolveTarget.isEmpty()) {
                        apply(out, new LinkSpan(LinkSpan.WIKI, resolveTarget), textStart, textEnd);
                    }
                    i = close + 2;
                    plainStart = i;
                    continue;
                }
            }

            if (c == '[' && i + 1 < n && source.charAt(i + 1) == '[') {
                int close = boundedIndexOf(source, "]]", i + 2);
                if (close > 0) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    String inner = source.substring(i + 2, close);
                    String target = inner;
                    String label = inner;
                    int pipe = inner.indexOf('|');
                    if (pipe >= 0) {
                        target = inner.substring(0, pipe).trim();
                        label = inner.substring(pipe + 1).trim();
                    }
                    int hash = target.indexOf('#');
                    String resolveTarget = hash > 0 ? target.substring(0, hash) : target;
                    boolean exists = resolver == null || resolver.exists(resolveTarget);
                    if (keepMarkers) appendMarker(out, "[[");
                    int textStart = out.length();
                    out.append(keepMarkers ? inner : label);
                    int textEnd = out.length();
                    if (keepMarkers) appendMarker(out, "]]");
                    int color = exists ? palette.link : palette.linkUnresolved;
                    apply(out, new ForegroundColorSpan(color), textStart, textEnd);
                    apply(out, new LinkSpan(LinkSpan.WIKI, resolveTarget), textStart, textEnd);
                    i = close + 2;
                    plainStart = i;
                    continue;
                }
            }

            if (c == '!' && i + 1 < n && source.charAt(i + 1) == '[') {
                int[] link = matchLink(source, i + 1);
                if (link != null) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    String alt = source.substring(i + 2, link[0]);
                    int textStart = out.length();
                    out.append(keepMarkers ? source.substring(i, link[1]) : (alt.isEmpty() ? "🖼" : alt));
                    apply(out, new ForegroundColorSpan(palette.marker), textStart, out.length());
                    i = link[1];
                    plainStart = i;
                    continue;
                }
            }

            if (c == '[') {
                int[] link = matchLink(source, i);
                if (link != null) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    String label = source.substring(i + 1, link[0]);
                    String url = source.substring(link[0] + 2, link[1] - 1).trim();
                    if (keepMarkers) appendMarker(out, "[");
                    int textStart = out.length();
                    out.append(label);
                    int textEnd = out.length();
                    if (keepMarkers) appendMarker(out, source.substring(link[0], link[1]));
                    apply(out, new ForegroundColorSpan(palette.link), textStart, textEnd);
                    apply(out, new LinkSpan(LinkSpan.URL, url), textStart, textEnd);
                    i = link[1];
                    plainStart = i;
                    continue;
                }

                if (i + 2 < n && source.charAt(i + 1) == '^') {
                    int close = closeSquareBracket(source, i);
                    if (close > i + 2) {
                        if (i > plainStart) out.append(source, plainStart, i);
                        String id = source.substring(i + 2, close);
                        boolean exists = referenceResolver == null || referenceResolver.hasFootnote(id);
                        if (keepMarkers) appendMarker(out, "[^");
                        else appendMarker(out, "[");
                        int textStart = out.length();
                        out.append(id);
                        int textEnd = out.length();
                        appendMarker(out, "]");
                        int color = exists ? palette.link : palette.linkUnresolved;
                        apply(out, new ForegroundColorSpan(color), textStart, textEnd);
                        apply(out, new RelativeSizeSpan(0.78f), textStart, textEnd);
                        apply(out, new SuperscriptSpan(), textStart, textEnd);
                        apply(out, new LinkSpan(LinkSpan.FOOTNOTE, id), textStart, textEnd);
                        i = close + 1;
                        plainStart = i;
                        continue;
                    }
                }

                ReferenceMatch reference = matchReferenceLink(source, i);
                if (reference != null && referenceResolver != null) {
                    String url = referenceResolver.referenceTarget(reference.key);
                    if (url != null && !url.isEmpty()) {
                        if (i > plainStart) out.append(source, plainStart, i);
                        if (keepMarkers) appendMarker(out, "[");
                        int textStart = out.length();
                        out.append(reference.label);
                        int textEnd = out.length();
                        if (keepMarkers) appendMarker(out,
                                source.substring(reference.closeLabel, reference.end));
                        apply(out, new ForegroundColorSpan(palette.link), textStart, textEnd);
                        apply(out, new LinkSpan(LinkSpan.URL, url), textStart, textEnd);
                        i = reference.end;
                        plainStart = i;
                        continue;
                    }
                }
            }

            if (c == '*' || c == '_') {
                int run = runLength(source, i, c);
                if (run >= 1 && run <= 3 && !isSpaceAt(source, i + run)) {
                    int close = indexOfRun(source, i + run, c, run);
                    if (close > i + run) {
                        if (i > plainStart) out.append(source, plainStart, i);
                        String body = source.substring(i + run, close);
                        String marker = source.substring(i, i + run);
                        if (keepMarkers) appendMarker(out, marker);
                        int textStart = out.length();
                        out.append(renderNested(body, keepMarkers));
                        int textEnd = out.length();
                        if (keepMarkers) appendMarker(out, marker);
                        int style = run == 1 ? Typeface.ITALIC
                                : run == 2 ? Typeface.BOLD : Typeface.BOLD_ITALIC;
                        apply(out, new StyleSpan(style), textStart, textEnd);
                        i = close + run;
                        plainStart = i;
                        continue;
                    }
                }
            }

            if (c == '~' && i + 1 < n && source.charAt(i + 1) == '~') {
                int close = boundedIndexOf(source, "~~", i + 2);
                if (close > 0) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    if (keepMarkers) appendMarker(out, "~~");
                    int textStart = out.length();
                    out.append(source, i + 2, close);
                    int textEnd = out.length();
                    if (keepMarkers) appendMarker(out, "~~");
                    apply(out, new StrikethroughSpan(), textStart, textEnd);
                    i = close + 2;
                    plainStart = i;
                    continue;
                }
            }

            if (c == '=' && i + 1 < n && source.charAt(i + 1) == '=') {
                int close = boundedIndexOf(source, "==", i + 2);
                if (close > 0) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    if (keepMarkers) appendMarker(out, "==");
                    int textStart = out.length();
                    out.append(source, i + 2, close);
                    int textEnd = out.length();
                    if (keepMarkers) appendMarker(out, "==");
                    apply(out, new BackgroundColorSpan(palette.highlight), textStart, textEnd);
                    i = close + 2;
                    plainStart = i;
                    continue;
                }
            }

            if (c == '#' && isTagStart(source, i)) {
                int end = i + 1;
                while (end < n && isTagChar(source.charAt(end))) end++;
                if (end > i + 1) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    int textStart = out.length();
                    out.append(source, i, end);
                    int textEnd = out.length();
                    apply(out, new ForegroundColorSpan(palette.tag), textStart, textEnd);
                    apply(out, new LinkSpan(LinkSpan.TAG, source.substring(i + 1, end)),
                            textStart, textEnd);
                    i = end;
                    plainStart = i;
                    continue;
                }
            }

            if (c == 'h' && looksLikeUrl(source, i)) {
                int end = i;
                while (end < n && !Character.isWhitespace(source.charAt(end))
                        && source.charAt(end) != ')' && source.charAt(end) != ']') end++;
                if (end > i) {
                    if (i > plainStart) out.append(source, plainStart, i);
                    String url = source.substring(i, end);
                    int textStart = out.length();
                    out.append(url);
                    int textEnd = out.length();
                    apply(out, new ForegroundColorSpan(palette.link), textStart, textEnd);
                    apply(out, new LinkSpan(LinkSpan.URL, url), textStart, textEnd);
                    i = end;
                    plainStart = i;
                    continue;
                }
            }

            i++;
        }
        if (plainStart < n) {
            out.append(source, plainStart, n);
        }
        return out;
    }

    /** Emphasis can nest; recurse for the body but never for markers. */
    private CharSequence renderNested(String body, boolean keepMarkers) {
        if (body.length() > MAX_SPAN_LOOKAHEAD) return body;
        if (body.indexOf('*') < 0 && body.indexOf('`') < 0 && body.indexOf('_') < 0
                && body.indexOf('[') < 0 && body.indexOf('!') < 0 && body.indexOf('~') < 0
                && body.indexOf('=') < 0) {
            return body;
        }
        return build(body, keepMarkers);
    }

    private void appendMarker(SpannableStringBuilder out, String marker) {
        int start = out.length();
        out.append(marker);
        apply(out, new ForegroundColorSpan(palette.marker), start, out.length());
    }

    private static void apply(SpannableStringBuilder out, Object span, int start, int end) {
        if (end > start) out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---------------------------------------------------------------- scanners

    private static boolean isMarkerChar(char c) {
        return c == '*' || c == '_' || c == '`' || c == '~' || c == '[' || c == ']'
                || c == '#' || c == '\\' || c == '=' || c == '!' || c == '(' || c == ')';
    }

    private static int runLength(String s, int from, char c) {
        int i = from;
        while (i < s.length() && s.charAt(i) == c) i++;
        return i - from;
    }

    private static int indexOfRun(String s, int from, char c, int length) {
        int limit = Math.min(s.length(), from + MAX_SPAN_LOOKAHEAD);
        for (int i = from; i + length <= limit; i++) {
            if (s.charAt(i) != c) continue;
            if (i > 0 && s.charAt(i - 1) == '\\') continue;
            int run = runLength(s, i, c);
            if (run >= length) return i;
            i += run - 1;
        }
        return -1;
    }

    private static int boundedIndexOf(String s, String target, int from) {
        int limit = Math.min(s.length(), from + MAX_SPAN_LOOKAHEAD);
        int idx = s.indexOf(target, from);
        return (idx >= 0 && idx < limit) ? idx : -1;
    }

    private static boolean isSpaceAt(String s, int index) {
        return index >= s.length() || Character.isWhitespace(s.charAt(index));
    }

    /**
     * Matches {@code [label](target)} starting at the {@code [}.
     *
     * @return {@code {indexOfClosingBracket, indexAfterClosingParen}} or null
     */
    private static int[] matchLink(String s, int start) {
        int depth = 0;
        int i = start;
        int n = Math.min(s.length(), start + MAX_SPAN_LOOKAHEAD);
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) break;
            } else if (c == '\n') return null;
            i++;
        }
        if (i >= n || depth != 0) return null;
        int closeBracket = i;
        if (closeBracket + 1 >= n || s.charAt(closeBracket + 1) != '(') return null;
        int paren = 1;
        int j = closeBracket + 2;
        while (j < n) {
            char c = s.charAt(j);
            if (c == '(') paren++;
            else if (c == ')') {
                paren--;
                if (paren == 0) return new int[]{closeBracket, j + 1};
            } else if (c == '\n') return null;
            j++;
        }
        return null;
    }

    /** Parsed reference syntax for {@code [label][key]}, {@code [label][]} or {@code [label]}. */
    private static final class ReferenceMatch {
        final String label;
        final String key;
        final int closeLabel;
        final int end;

        ReferenceMatch(String label, String key, int closeLabel, int end) {
            this.label = label;
            this.key = key;
            this.closeLabel = closeLabel;
            this.end = end;
        }
    }

    private static ReferenceMatch matchReferenceLink(String source, int start) {
        int closeLabel = closeSquareBracket(source, start);
        if (closeLabel <= start + 1 || source.charAt(start + 1) == '^') return null;
        String label = source.substring(start + 1, closeLabel);
        int after = closeLabel + 1;
        if (after < source.length() && source.charAt(after) == '[') {
            int closeKey = closeSquareBracket(source, after);
            if (closeKey < 0) return null;
            String key = source.substring(after + 1, closeKey);
            return new ReferenceMatch(label, key.isEmpty() ? label : key, closeLabel, closeKey + 1);
        }
        return new ReferenceMatch(label, label, closeLabel, closeLabel + 1);
    }

    /** Finds the close for a single square-bracket construct, respecting escapes. */
    private static int closeSquareBracket(String source, int start) {
        int limit = Math.min(source.length(), start + MAX_SPAN_LOOKAHEAD);
        for (int i = start + 1; i < limit; i++) {
            char c = source.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '\n') return -1;
            if (c == ']') return i;
        }
        return -1;
    }

    private static String embedTarget(String inner) {
        int pipe = inner.indexOf('|');
        return (pipe < 0 ? inner : inner.substring(0, pipe)).trim();
    }

    private static String embedLabel(String inner, String target) {
        int pipe = inner.indexOf('|');
        if (pipe >= 0) {
            String alias = inner.substring(pipe + 1).trim();
            if (!alias.isEmpty()) return alias;
        }
        String visible = target;
        int heading = visible.indexOf('#');
        if (heading >= 0) visible = visible.substring(0, heading);
        int slash = Math.max(visible.lastIndexOf('/'), visible.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < visible.length()) visible = visible.substring(slash + 1);
        return visible.isEmpty() ? "embed" : visible;
    }

    private static String stripHeading(String target) {
        int hash = target.indexOf('#');
        return hash < 0 ? target : target.substring(0, hash).trim();
    }

    private static boolean isLikelyAttachment(String target) {
        String lower = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")
                || lower.endsWith(".pdf") || lower.endsWith(".mp3") || lower.endsWith(".wav")
                || lower.endsWith(".mp4") || lower.endsWith(".webm");
    }

    private static boolean isTagStart(String s, int index) {
        if (index > 0) {
            char before = s.charAt(index - 1);
            if (Character.isLetterOrDigit(before) || before == '#' || before == '&') return false;
        }
        return index + 1 < s.length() && Character.isLetter(s.charAt(index + 1));
    }

    private static boolean isTagChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/';
    }

    private static boolean looksLikeUrl(String s, int index) {
        return s.startsWith("http://", index) || s.startsWith("https://", index);
    }
}
