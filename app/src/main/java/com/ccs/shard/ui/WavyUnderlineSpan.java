package com.ccs.shard.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Spanned;
import android.text.style.LineBackgroundSpan;

/**
 * The red squiggle under something that looks wrong.
 *
 * <p>Implemented as a {@link LineBackgroundSpan} because that is the only span
 * type given a {@link Canvas} and the line's geometry. A line-background span is
 * not told which characters it covers, so it asks the {@link Spanned} for its own
 * range and intersects that with the line being drawn — which is what makes a
 * squiggle that spans several lines wrap correctly.
 */
public final class WavyUnderlineSpan implements LineBackgroundSpan {

    private final int color;
    private final float amplitude;
    private final float wavelength;
    private final float strokeWidth;
    private final Path path = new Path();
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WavyUnderlineSpan(int color, float density) {
        this.color = color;
        this.amplitude = 1.7f * density;
        this.wavelength = 3.6f * density;
        this.strokeWidth = 1.25f * density;
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(strokeWidth);
        wavePaint.setColor(color);
    }

    public int color() { return color; }

    @Override
    public void drawBackground(Canvas canvas, Paint paint, int left, int right,
                               int top, int baseline, int bottom,
                               CharSequence text, int start, int end, int lineNumber) {
        if (!(text instanceof Spanned)) return;
        Spanned spanned = (Spanned) text;
        int spanStart = spanned.getSpanStart(this);
        int spanEnd = spanned.getSpanEnd(this);
        if (spanStart < 0 || spanEnd <= spanStart) return;

        // Clip to the part of this span that falls on the line being drawn.
        int from = Math.max(spanStart, start);
        int to = Math.min(spanEnd, end);
        if (to <= from) return;

        float xStart = left + paint.measureText(text, start, from);
        float xEnd = left + paint.measureText(text, start, to);
        if (xEnd <= xStart) xEnd = xStart + wavelength;

        // Sit just below the baseline, inside the descent, so the squiggle never
        // collides with the glyphs or with the line beneath.
        float y = baseline + Math.min(bottom - baseline, amplitude * 2f + strokeWidth) - strokeWidth;

        path.reset();
        path.moveTo(xStart, y);
        boolean up = true;
        for (float x = xStart; x < xEnd; x += wavelength) {
            float next = Math.min(x + wavelength, xEnd);
            path.quadTo((x + next) / 2f, up ? y - amplitude * 2f : y + amplitude * 2f,
                    next, y);
            up = !up;
        }
        canvas.drawPath(path, wavePaint);
    }
}
