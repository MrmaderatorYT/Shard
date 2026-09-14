package com.ccs.shard.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

/** EditText with IDE-style line numbers gutter and caret movement listening. */
public final class TexEditText extends AppCompatEditText {

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int start, int end);
    }

    private OnSelectionChangedListener selectionChangedListener;
    private Paint lineNumPaint;
    private Paint dividerPaint;
    private float density;
    private int gutterWidth;
    private int customPaddingTop;
    private int customPaddingEnd;
    private int customPaddingBottom;
    private boolean updatingPadding;

    public TexEditText(Context context) {
        super(context);
        init();
    }

    public TexEditText(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TexEditText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        customPaddingTop = (int) (14 * density);
        customPaddingEnd = (int) (16 * density);
        customPaddingBottom = (int) (28 * density);

        lineNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lineNumPaint.setTextSize(getTextSize() * 0.76f);
        lineNumPaint.setTypeface(Typeface.MONOSPACE);
        lineNumPaint.setColor(0x80888888);
        lineNumPaint.setTextAlign(Paint.Align.RIGHT);

        dividerPaint = new Paint();
        dividerPaint.setColor(0x1F888888);
        dividerPaint.setStrokeWidth(density);

        updateGutterPadding();
    }

    private void updateGutterPadding() {
        int digits = Math.max(3, String.valueOf(Math.max(1, getLineCount())).length());
        gutterWidth = (int) ((digits * 8.5f + 14f) * density);
        int targetPaddingStart = gutterWidth + (int) (8 * density);

        if (getPaddingStart() != targetPaddingStart) {
            updatingPadding = true;
            setPaddingRelative(targetPaddingStart, getPaddingTop(), getPaddingEnd(), getPaddingBottom());
            updatingPadding = false;
        }
    }

    @Override
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        if (!updatingPadding) {
            customPaddingTop = top;
            customPaddingEnd = end;
            customPaddingBottom = bottom;
        }
        super.setPaddingRelative(start, top, end, bottom);
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        selectionChangedListener = listener;
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        updateGutterPadding();
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selStart, selEnd);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout != null) {
            int lineCount = getLineCount();
            int scrollY = getScrollY();
            int height = getHeight();
            int clipTop = scrollY;
            int clipBottom = scrollY + height;

            int firstLine = layout.getLineForVertical(clipTop);
            int lastLine = layout.getLineForVertical(clipBottom);

            float gutterTextX = gutterWidth - (6 * density);
            float dividerX = gutterWidth;

            canvas.drawLine(dividerX, clipTop, dividerX, clipBottom, dividerPaint);

            CharSequence text = getText();
            int textLength = text == null ? 0 : text.length();

            // Count logical line number at firstLine
            int logicalLine = 1;
            for (int i = 0; i < firstLine && i < lineCount; i++) {
                int lineStart = layout.getLineStart(i);
                if (i == 0 || (lineStart > 0 && lineStart - 1 < textLength && text.charAt(lineStart - 1) == '\n')) {
                    logicalLine++;
                }
            }

            for (int i = firstLine; i <= lastLine && i < lineCount; i++) {
                int lineStart = layout.getLineStart(i);
                boolean isLogicalStart = (i == 0) || (lineStart > 0 && lineStart - 1 < textLength && text.charAt(lineStart - 1) == '\n');
                if (isLogicalStart) {
                    int baseline = layout.getLineBaseline(i);
                    canvas.drawText(String.valueOf(logicalLine), gutterTextX, baseline, lineNumPaint);
                    logicalLine++;
                }
            }
        }
        super.onDraw(canvas);
    }
}
