package com.ccs.shard.block;

/**
 * Marks a range of text as a link target.
 *
 * <p>Not a {@link android.text.style.ClickableSpan}: those need a
 * {@code MovementMethod}, and installing one on an {@code EditText} breaks text
 * selection and the caret. Block views instead resolve the tapped offset
 * themselves and look for this span, which keeps editing behaviour intact.
 */
public final class LinkSpan {

    public static final int WIKI = 0;
    public static final int URL = 1;
    public static final int TAG = 2;
    /** A document-local GFM footnote id. */
    public static final int FOOTNOTE = 3;

    public final int kind;
    /** Note title, URL or tag name depending on {@link #kind}. */
    public final String target;

    public LinkSpan(int kind, String target) {
        this.kind = kind;
        this.target = target == null ? "" : target;
    }
}
