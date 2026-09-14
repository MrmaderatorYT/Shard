package com.ccs.shard.graph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import com.ccs.shard.R;
import com.ccs.shard.ui.Ui;

/**
 * Draws and navigates the note graph.
 *
 * <p>Rendering is built for a slow GPU and a slow CPU:
 * <ul>
 *   <li>Edges are batched into one {@code float[]} and drawn with a single
 *       {@code drawLines} call rather than one {@code drawLine} per edge.</li>
 *   <li>Nodes and edges outside the viewport are skipped.</li>
 *   <li>Labels only render past a zoom threshold, and only for nodes big enough
 *       to carry one — a thousand overlapping labels is both slow and useless.</li>
 *   <li>Nothing allocates in {@code onDraw}.</li>
 * </ul>
 *
 * <p>Gestures are the ones people expect from a map: drag to pan, pinch to zoom,
 * fling to coast, double-tap a node to open it.
 */
public final class GraphView extends View {

    public interface Listener {
        void onNodeSelected(int index, String key, String label, int kind);
        void onNodeOpened(int index, String key, int kind);
        void onSelectionCleared();
    }

    private static final float MIN_SCALE = 0.08f;
    private static final float MAX_SCALE = 4.5f;
    /**
     * A label is only drawn when the node is at least this big on screen, in dp.
     * On a 1200-note vault every node had a label and the result was unreadable;
     * gating on apparent size means labels appear as you zoom in, which is what
     * makes a large graph navigable rather than decorative.
     */
    private static final float LABEL_MIN_RADIUS_DP = 7f;
    /** Hard cap on labels per frame, so a dense cluster cannot stall a frame. */
    private static final int MAX_LABELS = 140;

    private GraphModel model = new GraphModel();
    private ForceLayout layout;
    private Listener listener;

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Thin ring in the surface colour, so touching nodes stay distinguishable. */
    private final Paint nodeOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * Occupancy grid over the viewport, reused every frame. A label is only drawn
     * where nothing has been drawn yet, which is what keeps a dense cluster
     * readable instead of a pile of overlapping words.
     */
    private boolean[] labelCells = new boolean[0];
    private int labelGridColumns;
    private int labelGridRows;

    private float offsetX;
    private float offsetY;
    private float scale = 1f;
    private boolean autoFitPending = true;

    private int selected = -1;
    private boolean[] neighbourMask = new boolean[0];
    /** Hides nodes with fewer than this many connections. */
    private int minDegree;
    private boolean showLabels = true;

    /** Batched edge segments, reused across frames. */
    private float[] edgeBuffer = new float[0];
    private float[] highlightBuffer = new float[0];

    private int colorNote;
    private int colorTag;
    private int colorMissing;
    private int colorSelected;
    private int colorEdge;
    private int colorLabel;
    private int colorLabelHalo;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller scroller;

    public GraphView(Context context) {
        super(context);
        init();
    }

    public GraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        Context context = getContext();
        boolean light = Ui.isLight(Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF));

        colorNote = Ui.themeColor(context,
                com.google.android.material.R.attr.colorPrimary, 0xFF2F6FEB);
        colorTag = androidx.core.content.ContextCompat.getColor(context, R.color.hue_4);
        colorMissing = Ui.withAlpha(Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF787066), 0.5f);
        colorSelected = androidx.core.content.ContextCompat.getColor(context, R.color.hue_5);
        colorEdge = androidx.core.content.ContextCompat.getColor(context,
                light ? R.color.graph_edge_light : R.color.graph_edge_dark);
        colorLabel = Ui.themeColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);
        colorLabelHalo = Ui.themeColor(context,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);

        edgePaint.setColor(colorEdge);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(Ui.dp(getContext(), 1f));
        edgePaint.setStrokeCap(Paint.Cap.ROUND);

        edgeHighlightPaint.setColor(colorSelected);
        edgeHighlightPaint.setStyle(Paint.Style.STROKE);
        edgeHighlightPaint.setStrokeWidth(Ui.dp(getContext(), 1.8f));
        edgeHighlightPaint.setStrokeCap(Paint.Cap.ROUND);

        nodePaint.setStyle(Paint.Style.FILL);

        nodeRingPaint.setStyle(Paint.Style.STROKE);
        nodeRingPaint.setStrokeWidth(Ui.dp(getContext(), 2f));
        nodeRingPaint.setColor(colorSelected);

        nodeOutlinePaint.setStyle(Paint.Style.STROKE);
        nodeOutlinePaint.setStrokeWidth(Ui.dp(getContext(), 1.4f));
        nodeOutlinePaint.setColor(colorLabelHalo);

        labelPaint.setColor(colorLabel);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Ui.sp(getContext(), 11f));

        labelShadowPaint.setColor(colorLabelHalo);
        labelShadowPaint.setTextAlign(Paint.Align.CENTER);
        labelShadowPaint.setTextSize(Ui.sp(getContext(), 11f));
        labelShadowPaint.setStyle(Paint.Style.STROKE);
        labelShadowPaint.setStrokeWidth(Ui.dp(getContext(), 2.5f));

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        gestureDetector = new GestureDetector(getContext(), new GestureListener());
        scroller = new OverScroller(getContext());
        setWillNotDraw(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public GraphModel model() { return model; }

    public int nodeCount() { return model.nodeCount; }

    public int edgeCount() { return model.edgeCount; }

    /** Replaces the graph and starts a fresh layout pass. */
    public void setModel(GraphModel newModel) {
        if (layout != null) layout.cancel();
        model = newModel == null ? new GraphModel() : newModel;
        selected = -1;
        neighbourMask = new boolean[model.nodeCount];
        edgeBuffer = new float[model.edgeCount * 4];
        highlightBuffer = new float[Math.min(model.edgeCount, 512) * 4];
        autoFitPending = true;
        layout = new ForceLayout(model, finished -> {
            if (autoFitPending && getWidth() > 0) {
                fitToScreen();
                autoFitPending = false;
            }
            invalidate();
        });
        layout.start();
        invalidate();
    }

    public void stopLayout() {
        if (layout != null) layout.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLayout();
        super.onDetachedFromWindow();
    }

    /** Frames the whole graph with a small margin. */
    public void fitToScreen() {
        if (model.nodeCount == 0 || getWidth() == 0) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < model.nodeCount; i++) {
            if (!isVisible(i)) continue;
            if (model.x[i] < minX) minX = model.x[i];
            if (model.x[i] > maxX) maxX = model.x[i];
            if (model.y[i] < minY) minY = model.y[i];
            if (model.y[i] > maxY) maxY = model.y[i];
        }
        if (minX > maxX) return;
        float margin = Ui.dp(getContext(), 48);
        float graphWidth = Math.max(1f, maxX - minX);
        float graphHeight = Math.max(1f, maxY - minY);
        float scaleX = (getWidth() - margin * 2) / graphWidth;
        float scaleY = (getHeight() - margin * 2) / graphHeight;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(scaleX, scaleY)));
        float centreX = (minX + maxX) / 2f;
        float centreY = (minY + maxY) / 2f;
        offsetX = getWidth() / 2f - centreX * scale;
        offsetY = getHeight() / 2f - centreY * scale;
        invalidate();
    }

    /** Centres the view on one node without changing zoom. */
    public void centreOn(int index) {
        if (index < 0 || index >= model.nodeCount) return;
        offsetX = getWidth() / 2f - model.x[index] * scale;
        offsetY = getHeight() / 2f - model.y[index] * scale;
        invalidate();
    }

    /** Hides sparsely connected nodes, which is how a big vault becomes readable. */
    public void setMinDegree(int value) {
        if (minDegree == value) return;
        minDegree = Math.max(0, value);
        invalidate();
    }

    public int minDegree() { return minDegree; }

    public void setShowLabels(boolean value) {
        if (showLabels == value) return;
        showLabels = value;
        invalidate();
    }

    private boolean isVisible(int index) {
        return model.degree[index] >= minDegree;
    }

    public void select(int index) {
        selected = index;
        java.util.Arrays.fill(neighbourMask, false);
        if (index >= 0) {
            for (int neighbour : model.neighbours(index)) {
                if (neighbour >= 0 && neighbour < neighbourMask.length) {
                    neighbourMask[neighbour] = true;
                }
            }
        }
        invalidate();
    }

    public int selectedIndex() { return selected; }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (model.nodeCount == 0) return;

        int width = getWidth();
        int height = getHeight();
        boolean dimming = selected >= 0;

        // Pass 1: edges, batched into as few draw calls as possible.
        int cursor = 0;
        int highlightCursor = 0;
        for (int e = 0; e < model.edgeCount; e++) {
            int a = model.edgeFrom[e];
            int b = model.edgeTo[e];
            if (!isVisible(a) || !isVisible(b)) continue;
            float ax = model.x[a] * scale + offsetX;
            float ay = model.y[a] * scale + offsetY;
            float bx = model.x[b] * scale + offsetX;
            float by = model.y[b] * scale + offsetY;
            // Cheap segment-vs-viewport rejection.
            if ((ax < 0 && bx < 0) || (ax > width && bx > width)
                    || (ay < 0 && by < 0) || (ay > height && by > height)) {
                continue;
            }
            boolean highlighted = dimming && (a == selected || b == selected);
            if (highlighted) {
                if (highlightCursor + 4 <= highlightBuffer.length) {
                    highlightBuffer[highlightCursor++] = ax;
                    highlightBuffer[highlightCursor++] = ay;
                    highlightBuffer[highlightCursor++] = bx;
                    highlightBuffer[highlightCursor++] = by;
                }
            } else if (cursor + 4 <= edgeBuffer.length) {
                edgeBuffer[cursor++] = ax;
                edgeBuffer[cursor++] = ay;
                edgeBuffer[cursor++] = bx;
                edgeBuffer[cursor++] = by;
            }
        }
        edgePaint.setAlpha(dimming ? 40 : Color.alpha(colorEdge));
        if (cursor > 0) canvas.drawLines(edgeBuffer, 0, cursor, edgePaint);
        if (highlightCursor > 0) {
            canvas.drawLines(highlightBuffer, 0, highlightCursor, edgeHighlightPaint);
        }

        // Pass 2: nodes.
        float labelMinRadius = Ui.dp(getContext(), LABEL_MIN_RADIUS_DP);
        int labelCount = 0;
        int labelCandidates = 0;
        for (int i = 0; i < model.nodeCount; i++) {
            if (!isVisible(i)) continue;
            float cx = model.x[i] * scale + offsetX;
            float cy = model.y[i] * scale + offsetY;
            float radius = radiusOf(i) * scale;
            if (cx + radius < 0 || cx - radius > width
                    || cy + radius < 0 || cy - radius > height) {
                continue;
            }
            int alpha = 255;
            if (dimming && i != selected && !neighbourMask[i]) alpha = 60;
            float drawRadius = Math.max(1.5f, radius);
            nodePaint.setColor(colorFor(i));
            nodePaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, drawRadius, nodePaint);
            if (drawRadius > Ui.dp(getContext(), 3)) {
                nodeOutlinePaint.setAlpha(alpha);
                canvas.drawCircle(cx, cy, drawRadius, nodeOutlinePaint);
            }

            if (i == selected) {
                canvas.drawCircle(cx, cy, Math.max(1.5f, radius) + Ui.dp(getContext(), 3),
                        nodeRingPaint);
            }
            if (radius >= labelMinRadius || i == selected
                    || (dimming && neighbourMask[i])) {
                labelCandidates++;
            }
        }

        // Pass 3: labels last, so a neighbouring node can never paint over one.
        if (!showLabels || labelCandidates == 0) return;
        float labelSize = Ui.sp(getContext(), 11f);
        labelPaint.setTextSize(labelSize);
        labelShadowPaint.setTextSize(labelSize);
        prepareLabelGrid(width, height, labelSize);
        for (int i = 0; i < model.nodeCount && labelCount < MAX_LABELS; i++) {
            if (!isVisible(i)) continue;
            float cx = model.x[i] * scale + offsetX;
            float cy = model.y[i] * scale + offsetY;
            float radius = radiusOf(i) * scale;
            if (cx + radius < 0 || cx - radius > width
                    || cy + radius < 0 || cy - radius > height) {
                continue;
            }
            boolean wanted = radius >= labelMinRadius || i == selected
                    || (dimming && neighbourMask[i]);
            if (!wanted) continue;

            String text = model.label[i];
            if (text == null) continue;
            if (text.length() > 22) text = text.substring(0, 21) + "…";
            float baseline = cy + radius + labelSize + Ui.dp(getContext(), 2);
            float textWidth = labelPaint.measureText(text);
            boolean forced = i == selected || (dimming && neighbourMask[i]);
            if (!forced && !claimLabelSpace(cx, baseline, textWidth, labelSize)) continue;

            int alpha = 255;
            if (dimming && i != selected && !neighbourMask[i]) alpha = 60;
            labelPaint.setAlpha(alpha);
            labelShadowPaint.setAlpha(alpha);
            canvas.drawText(text, cx, baseline, labelShadowPaint);
            canvas.drawText(text, cx, baseline, labelPaint);
            labelCount++;
        }
    }

    private void prepareLabelGrid(int width, int height, float labelSize) {
        int cellSize = Math.max(1, Math.round(labelSize * 1.35f));
        labelGridColumns = Math.max(1, width / cellSize + 1);
        labelGridRows = Math.max(1, height / cellSize + 1);
        int needed = labelGridColumns * labelGridRows;
        if (labelCells.length < needed) labelCells = new boolean[needed];
        else java.util.Arrays.fill(labelCells, 0, needed, false);
    }

    /**
     * Reserves the cells a label would cover.
     *
     * @return false when the space is already taken, meaning skip this label
     */
    private boolean claimLabelSpace(float centreX, float baseline,
                                    float textWidth, float labelSize) {
        int cellSize = Math.max(1, Math.round(labelSize * 1.35f));
        int left = (int) ((centreX - textWidth / 2f) / cellSize);
        int right = (int) ((centreX + textWidth / 2f) / cellSize);
        int row = (int) ((baseline - labelSize / 2f) / cellSize);
        if (row < 0 || row >= labelGridRows) return false;
        left = Math.max(0, left);
        right = Math.min(labelGridColumns - 1, right);
        if (right < left) return false;
        for (int c = left; c <= right; c++) {
            if (labelCells[row * labelGridColumns + c]) return false;
        }
        for (int c = left; c <= right; c++) {
            labelCells[row * labelGridColumns + c] = true;
        }
        return true;
    }

    private float radiusOf(int index) {
        float base = model.kind[index] == GraphModel.KIND_TAG ? 5f : 6f;
        // Square-root scaling keeps hubs prominent, and the cap stops a tag with
        // four hundred notes from swallowing the canvas.
        return Math.min(30f, base + (float) Math.sqrt(model.degree[index]) * 2.6f);
    }

    private int colorFor(int index) {
        switch (model.kind[index]) {
            case GraphModel.KIND_TAG: return colorTag;
            case GraphModel.KIND_MISSING: return colorMissing;
            default: return index == selected ? colorSelected : colorNote;
        }
    }

    // ---------------------------------------------------------------- gestures

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        handled = gestureDetector.onTouchEvent(event) || handled;
        return handled || super.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offsetX = scroller.getCurrX();
            offsetY = scroller.getCurrY();
            postInvalidateOnAnimation();
        }
    }

    /** Node under a screen point, or -1. Prefers the smallest hit so dense areas stay usable. */
    private int nodeAt(float screenX, float screenY) {
        float slop = Ui.dp(getContext(), 12);
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < model.nodeCount; i++) {
            if (!isVisible(i)) continue;
            float cx = model.x[i] * scale + offsetX;
            float cy = model.y[i] * scale + offsetY;
            float radius = Math.max(Ui.dp(getContext(), 8), radiusOf(i) * scale) + slop;
            float dx = screenX - cx;
            float dy = screenY - cy;
            float distance = dx * dx + dy * dy;
            if (distance <= radius * radius && distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            scroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent down, MotionEvent move,
                                float distanceX, float distanceY) {
            offsetX -= distanceX;
            offsetY -= distanceY;
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent down, MotionEvent up,
                               float velocityX, float velocityY) {
            scroller.forceFinished(true);
            scroller.fling((int) offsetX, (int) offsetY,
                    (int) velocityX / 2, (int) velocityY / 2,
                    Integer.MIN_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent event) {
            int index = nodeAt(event.getX(), event.getY());
            select(index);
            if (listener == null) return true;
            if (index >= 0) {
                Ui.hapticTap(GraphView.this);
                listener.onNodeSelected(index, model.key[index],
                        model.label[index], model.kind[index]);
            } else {
                listener.onSelectionCleared();
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            int index = nodeAt(event.getX(), event.getY());
            if (index >= 0) {
                if (listener != null) {
                    listener.onNodeOpened(index, model.key[index], model.kind[index]);
                }
                return true;
            }
            // Double-tap on empty space zooms toward the finger.
            zoomAround(event.getX(), event.getY(), scale < 1f ? 1.8f : 0.6f);
            return true;
        }

        @Override
        public void onLongPress(MotionEvent event) {
            int index = nodeAt(event.getX(), event.getY());
            if (index >= 0) {
                Ui.hapticLongPress(GraphView.this);
                select(index);
                if (listener != null) {
                    listener.onNodeSelected(index, model.key[index],
                            model.label[index], model.kind[index]);
                }
            }
        }
    }

    private void zoomAround(float focusX, float focusY, float factor) {
        float target = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        float ratio = target / scale;
        offsetX = focusX - (focusX - offsetX) * ratio;
        offsetY = focusY - (focusY - offsetY) * ratio;
        scale = target;
        invalidate();
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            zoomAround(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
            return true;
        }
    }
}
