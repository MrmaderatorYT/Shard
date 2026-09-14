package com.ccs.shard.block;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.Layout;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import androidx.appcompat.widget.AppCompatEditText;

/**
 * The text field used by every text block.
 *
 * <p>Adds the three things a block editor needs from an {@code EditText} and the
 * platform does not give it:
 *
 * <ul>
 *   <li><b>Reliable backspace-at-start.</b> Soft keyboards usually delete via
 *       {@code deleteSurroundingText} rather than a {@code KEYCODE_DEL} event, so
 *       both paths are intercepted. This is what lets Backspace on an empty block
 *       merge it into the one above, the way every block editor behaves.</li>
 *   <li><b>Caret geometry</b>, so the slash palette can be positioned at the
 *       caret rather than at the block.</li>
 *   <li><b>A no-op Enter action</b>, so the IME never inserts a raw newline
 *       behind the editor's back; splitting is handled by the adapter.</li>
 * </ul>
 */
public final class BlockEditText extends AppCompatEditText {

    public interface Listener {
        /** Backspace pressed with the caret at offset 0 and nothing selected. */
        boolean onBackspaceAtStart();

        /** Tab or Shift+Tab pressed while editing a block. */
        boolean onTab(boolean shift);
    }

    private Listener listener;
    /** True only while Shift+Enter is inserting a soft break into this block. */
    private boolean insertingSoftLineBreak;

    public BlockEditText(Context context) {
        super(context);
        setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        setIncludeFontPadding(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private boolean atStart() {
        return getSelectionStart() == 0 && getSelectionEnd() == 0;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed()) {
            Editable text = getText();
            if (text == null) return true;
            int start = Math.max(0, Math.min(getSelectionStart(), text.length()));
            int end = Math.max(0, Math.min(getSelectionEnd(), text.length()));
            if (start > end) {
                int swap = start;
                start = end;
                end = swap;
            }
            insertingSoftLineBreak = true;
            try {
                text.replace(start, end, "\n");
            } finally {
                insertingSoftLineBreak = false;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_TAB && listener != null) {
            if (listener.onTab(event.isShiftPressed())) return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && atStart() && listener != null) {
            if (listener.onBackspaceAtStart()) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Lets the adapter's newline filter distinguish Shift+Enter from Enter. */
    public boolean isInsertingSoftLineBreak() {
        return insertingSoftLineBreak;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection base = super.onCreateInputConnection(outAttrs);
        if (base == null) return null;
        return new InputConnectionWrapper(base, true) {
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength == 1 && afterLength == 0 && atStart() && listener != null) {
                    if (listener.onBackspaceAtStart()) return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                        && atStart() && listener != null) {
                    if (listener.onBackspaceAtStart()) return true;
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    /** Screen-space rectangle of the caret, for anchoring popups. */
    public Rect caretRectOnScreen() {
        int offset = Math.max(0, getSelectionStart());
        Layout layout = getLayout();
        int[] location = new int[2];
        getLocationOnScreen(location);
        if (layout == null) {
            return new Rect(location[0], location[1],
                    location[0], location[1] + getHeight());
        }
        int line = layout.getLineForOffset(offset);
        int x = Math.round(layout.getPrimaryHorizontal(offset));
        int top = layout.getLineTop(line);
        int bottom = layout.getLineBottom(line);
        int left = location[0] + getPaddingLeft() + x - getScrollX();
        int topY = location[1] + getPaddingTop() + top - getScrollY();
        int bottomY = location[1] + getPaddingTop() + bottom - getScrollY();
        return new Rect(left, topY, left, bottomY);
    }

    /** Text offset under a touch point, or -1 when there is no layout yet. */
    public int offsetForPosition(float x, float y) {
        Layout layout = getLayout();
        if (layout == null) return -1;
        int line = layout.getLineForVertical(
                Math.round(y - getPaddingTop() + getScrollY()));
        return layout.getOffsetForHorizontal(line,
                x - getPaddingLeft() + getScrollX());
    }
}
