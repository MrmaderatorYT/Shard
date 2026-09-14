package com.ccs.shard.canvas;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.ccs.shard.ui.Ui;
import com.ccs.shard.R;
import com.ccs.shard.block.ImageLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An infinite, pannable canvas of cards and connections.
 *
 * <p>Interaction follows what a canvas on a touchscreen has to be: drag empty
 * space to pan, drag a card to move it, pinch to zoom, double-tap empty space to
 * add a card, long-press a card for its menu. Connections are made by putting a
 * card in "connect" mode and tapping the target, which needs no precise dragging
 * from a small handle.
 *
 * <p>Text layout for the cards is cached per node and only rebuilt when the text
 * or width changes; laying out every card's text on every frame was the main cost
 * in the previous implementation.
 */
public final class CanvasView extends View {

    public interface Listener {
        void onSelectionChanged(CanvasDoc.Node node);
        void onNodeMenu(CanvasDoc.Node node, float screenX, float screenY);
        void onEmptyMenu(float worldX, float worldY, float screenX, float screenY);
        void onNodeActivated(CanvasDoc.Node node);
        void onDocumentEdited();
    }

    private static final float MIN_SCALE = 0.2f;
    private static final float MAX_SCALE = 3f;

    private CanvasDoc doc = new CanvasDoc();
    private Listener listener;

    private float offsetX;
    private float offsetY;
    /** Zoom factor; one world unit is one dp when this is 1. */
    private float scale = 1f;
    private float density = 1f;

    private CanvasDoc.Node selected;
    private final Set<CanvasDoc.Node> selectedNodes = new LinkedHashSet<>();
    private CanvasDoc.Node dragging;
    private CanvasDoc.Node resizing;
    private float dragOffsetX;
    private float dragOffsetY;
    private boolean connectMode;
    private boolean multiSelectMode;
    private boolean drawingMode;
    private CanvasDoc.Stroke drawing;
    private float lastDrawingX;
    private float lastDrawingY;
    private float alignmentGuideX = Float.NaN;
    private float alignmentGuideY = Float.NaN;

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint edgeLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drawingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint alignmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path edgePath = new Path();
    private final Path drawingPath = new Path();
    private final RectF cardRect = new RectF();

    private int colorCard;
    private int colorFileCard;
    private int colorStroke;
    private int colorAccent;
    private int colorText;
    private int colorMuted;

    /** Cached text layouts, keyed by node id. */
    private final Map<String, CachedLayout> layouts = new HashMap<>();
    private final Map<String, Bitmap> images = new HashMap<>();
    private final Set<String> loadingImages = new HashSet<>();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private static final class CachedLayout {
        final String text;
        final int width;
        final StaticLayout layout;

        CachedLayout(String text, int width, StaticLayout layout) {
            this.text = text;
            this.width = width;
            this.layout = layout;
        }
    }

    public CanvasView(Context context) {
        super(context);
        init();
    }

    public CanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        Context c = getContext();
        density = c.getResources().getDisplayMetrics().density;
        int surface = Ui.themeColor(c, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        boolean light = Ui.isLight(surface);
        colorCard = Ui.blend(surface, light ? 0xFF000000 : 0xFFFFFFFF, light ? 0.04f : 0.10f);
        colorAccent = Ui.themeColor(c,
                com.google.android.material.R.attr.colorPrimary, 0xFF2F6FEB);
        colorFileCard = Ui.blend(surface, colorAccent, 0.12f);
        colorStroke = Ui.themeColor(c,
                com.google.android.material.R.attr.colorOutlineVariant, 0x33808080);
        colorText = Ui.themeColor(c,
                com.google.android.material.R.attr.colorOnSurface, 0xFF37352F);
        colorMuted = Ui.themeColor(c,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF787066);

        cardPaint.setStyle(Paint.Style.FILL);
        cardStrokePaint.setStyle(Paint.Style.STROKE);
        cardStrokePaint.setStrokeWidth(Ui.dp(c, 1.2f));
        cardStrokePaint.setColor(colorStroke);
        selectedStrokePaint.setStyle(Paint.Style.STROKE);
        selectedStrokePaint.setStrokeWidth(Ui.dp(c, 2.2f));
        selectedStrokePaint.setColor(colorAccent);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(Ui.dp(c, 1.8f));
        edgePaint.setColor(Ui.withAlpha(colorMuted, 0.7f));
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(Ui.withAlpha(colorMuted, 0.7f));

        gridPaint.setStyle(Paint.Style.FILL);
        gridPaint.setColor(Ui.withAlpha(colorMuted, 0.16f));
        edgeLabelPaint.setColor(colorMuted);
        edgeLabelPaint.setTextSize(Ui.sp(c, 11f));
        edgeLabelPaint.setTextAlign(Paint.Align.CENTER);

        drawingPaint.setStyle(Paint.Style.STROKE);
        drawingPaint.setStrokeCap(Paint.Cap.ROUND);
        drawingPaint.setStrokeJoin(Paint.Join.ROUND);
        alignmentPaint.setStyle(Paint.Style.STROKE);
        alignmentPaint.setStrokeWidth(Ui.dp(c, 1f));
        alignmentPaint.setColor(Ui.withAlpha(colorAccent, 0.62f));
        alignmentPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{Ui.dp(c, 5), Ui.dp(c, 4)}, 0));

        textPaint.setColor(colorText);
        textPaint.setTextSize(Ui.sp(c, 13f));
        titlePaint.setColor(colorText);
        titlePaint.setTextSize(Ui.sp(c, 14f));
        titlePaint.setFakeBoldText(true);

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        gestureDetector = new GestureDetector(getContext(), new GestureListener());
        setWillNotDraw(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setDocument(CanvasDoc document) {
        this.doc = document == null ? new CanvasDoc() : document;
        selected = null;
        selectedNodes.clear();
        connectMode = false;
        multiSelectMode = false;
        drawingMode = false;
        drawing = null;
        layouts.clear();
        images.clear();
        loadingImages.clear();
        fitToContent();
        invalidate();
    }

    public CanvasDoc document() { return doc; }

    public CanvasDoc.Node selectedNode() { return selected; }

    public List<CanvasDoc.Node> selectedNodes() { return new ArrayList<>(selectedNodes); }

    public boolean isMultiSelectMode() { return multiSelectMode; }

    public void setMultiSelectMode(boolean value) {
        multiSelectMode = value;
        if (value) drawingMode = false;
        if (!value && selected != null) {
            selectedNodes.clear();
            selectedNodes.add(selected);
        }
        invalidate();
    }

    public boolean isDrawingMode() { return drawingMode; }

    public void setDrawingMode(boolean value) {
        drawingMode = value;
        if (value) {
            connectMode = false;
            multiSelectMode = false;
            clearSelection();
        }
        invalidate();
    }

    public boolean isConnectMode() { return connectMode; }

    public void setConnectMode(boolean value) {
        connectMode = value;
        if (value) drawingMode = false;
        invalidate();
    }

    /** Resolves a note title for a file card; supplied by the activity. */
    public interface TitleResolver {
        String titleOf(String notePath);
    }

    private TitleResolver titleResolver;

    public void setTitleResolver(TitleResolver resolver) {
        this.titleResolver = resolver;
        layouts.clear();
        invalidate();
    }

    // ---------------------------------------------------------------- viewport

    public void fitToContent() {
        float[] bounds = doc.bounds();
        if (bounds == null || getWidth() == 0) {
            scale = 1f;
            offsetX = getWidth() / 2f;
            offsetY = getHeight() / 2f;
            return;
        }
        float margin = Ui.dp(getContext(), 40);
        float contentWidth = Math.max(1f, bounds[2] - bounds[0]);
        float contentHeight = Math.max(1f, bounds[3] - bounds[1]);
        float scaleX = (getWidth() - margin * 2) / (contentWidth * density);
        float scaleY = (getHeight() - margin * 2) / (contentHeight * density);
        scale = Math.max(MIN_SCALE, Math.min(1.2f, Math.min(scaleX, scaleY)));
        float worldScale = worldScale();
        offsetX = getWidth() / 2f - (bounds[0] + contentWidth / 2f) * worldScale;
        offsetY = getHeight() / 2f - (bounds[1] + contentHeight / 2f) * worldScale;
        invalidate();
    }

    public void zoomBy(float factor) {
        zoomAround(getWidth() / 2f, getHeight() / 2f, factor);
    }

    private void zoomAround(float focusX, float focusY, float factor) {
        float target = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        float ratio = target / scale;
        offsetX = focusX - (focusX - offsetX) * ratio;
        offsetY = focusY - (focusY - offsetY) * ratio;
        scale = target;
        invalidate();
    }

    private float worldScale() { return density * scale; }

    private float toWorldX(float screenX) { return (screenX - offsetX) / worldScale(); }

    private float toWorldY(float screenY) { return (screenY - offsetY) / worldScale(); }

    /** Centre of the viewport in world coordinates, for placing new cards. */
    public float[] viewportCentreWorld() {
        return new float[]{toWorldX(getWidth() / 2f), toWorldY(getHeight() / 2f)};
    }

    /** Centres and selects a search result without changing its saved position. */
    public void focusOnNode(CanvasDoc.Node node) {
        if (node == null || !doc.nodes().contains(node)) return;
        scale = Math.max(0.7f, Math.min(1.2f, scale));
        float worldScale = worldScale();
        offsetX = getWidth() / 2f - node.centreX() * worldScale;
        offsetY = getHeight() / 2f - node.centreY() * worldScale;
        select(node);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth == 0 && width > 0) fitToContent();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        drawGrid(canvas);
        drawStrokes(canvas);
        drawAlignmentGuides(canvas);

        if (doc.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(Ui.withAlpha(colorMuted, 0.72f));
            textPaint.setTextSize(Ui.sp(getContext(), 14f));
            canvas.drawText(getContext().getString(R.string.canvas_empty_tip),
                    getWidth() / 2f, getHeight() / 2f, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(colorText);
        }

        // Groups form the background even when selected or imported out of order.
        for (CanvasDoc.Node node : doc.nodes()) {
            if (node.isGroup()) drawNode(canvas, node);
        }

        // Edges first so cards sit on top of their connections.
        for (CanvasDoc.Edge edge : doc.edges()) {
            CanvasDoc.Node from = doc.nodeById(edge.fromNode);
            CanvasDoc.Node to = doc.nodeById(edge.toNode);
            if (from == null || to == null) continue;
            drawEdge(canvas, edge, from, to);
        }

        for (CanvasDoc.Node node : doc.nodes()) {
            if (!node.isGroup()) drawNode(canvas, node);
        }
    }

    /** A dot grid, which gives the canvas a sense of place while panning. */
    private void drawGrid(Canvas canvas) {
        float step = 48f * worldScale();
        if (step < 14f) return;
        float radius = Math.min(Ui.dp(getContext(), 1.1f), step / 14f);
        float startX = offsetX % step;
        float startY = offsetY % step;
        for (float x = startX; x < getWidth(); x += step) {
            for (float y = startY; y < getHeight(); y += step) {
                canvas.drawCircle(x, y, radius, gridPaint);
            }
        }
    }

    /** Draws persisted freehand strokes below cards, in the same world coordinates. */
    private void drawStrokes(Canvas canvas) {
        for (CanvasDoc.Stroke stroke : doc.strokes()) {
            if (stroke == null || stroke.pointCount() == 0) continue;
            drawingPaint.setColor(strokeColor(stroke));
            drawingPaint.setStrokeWidth(Math.max(Ui.dp(getContext(), 0.8f),
                    stroke.width * worldScale()));
            if (stroke.pointCount() == 1) {
                float x = stroke.points.get(0) * worldScale() + offsetX;
                float y = stroke.points.get(1) * worldScale() + offsetY;
                // A tap has the same diameter as a line with this brush.
                drawingPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, drawingPaint.getStrokeWidth() / 2f, drawingPaint);
                drawingPaint.setStyle(Paint.Style.STROKE);
                continue;
            }
            drawingPath.reset();
            drawingPath.moveTo(stroke.points.get(0) * worldScale() + offsetX,
                    stroke.points.get(1) * worldScale() + offsetY);
            for (int i = 2; i + 1 < stroke.points.size(); i += 2) {
                drawingPath.lineTo(stroke.points.get(i) * worldScale() + offsetX,
                        stroke.points.get(i + 1) * worldScale() + offsetY);
            }
            canvas.drawPath(drawingPath, drawingPaint);
        }
    }

    private int strokeColor(CanvasDoc.Stroke stroke) {
        try {
            return stroke.color == null || stroke.color.isEmpty()
                    ? colorAccent : android.graphics.Color.parseColor(stroke.color);
        } catch (Throwable ignored) {
            return colorAccent;
        }
    }

    private void drawAlignmentGuides(Canvas canvas) {
        if (!Float.isNaN(alignmentGuideX)) {
            float x = alignmentGuideX * worldScale() + offsetX;
            canvas.drawLine(x, 0, x, getHeight(), alignmentPaint);
        }
        if (!Float.isNaN(alignmentGuideY)) {
            float y = alignmentGuideY * worldScale() + offsetY;
            canvas.drawLine(0, y, getWidth(), y, alignmentPaint);
        }
    }

    private void drawEdge(Canvas canvas, CanvasDoc.Edge edge,
                          CanvasDoc.Node from, CanvasDoc.Node to) {
        float worldScale = worldScale();
        float x1 = from.centreX() * worldScale + offsetX;
        float y1 = from.centreY() * worldScale + offsetY;
        float x2 = to.centreX() * worldScale + offsetX;
        float y2 = to.centreY() * worldScale + offsetY;

        // Connections entirely outside the viewport are invisible; skipping
        // their path, arrow and label is significant on imported 1000-node canvases.
        float margin = Ui.dp(getContext(), 32);
        if (Math.max(x1, x2) < -margin || Math.min(x1, x2) > getWidth() + margin
                || Math.max(y1, y2) < -margin || Math.min(y1, y2) > getHeight() + margin) {
            return;
        }

        // Trim the line to each card's edge so it does not run under the card.
        float[] start = edgePoint(from, edge.fromSide, x2, y2);
        float[] end = edgePoint(to, edge.toSide, x1, y1);
        x1 = start[0];
        y1 = start[1];
        x2 = end[0];
        y2 = end[1];

        edgePath.reset();
        edgePath.moveTo(x1, y1);
        float distance = Math.max(Ui.dp(getContext(), 36),
                Math.min(Ui.dp(getContext(), 160),
                        (float) Math.hypot(x2 - x1, y2 - y1) / 2f));
        float[] startVector = sideVector(edge.fromSide);
        float[] endVector = sideVector(edge.toSide);
        edgePath.cubicTo(x1 + startVector[0] * distance, y1 + startVector[1] * distance,
                x2 + endVector[0] * distance, y2 + endVector[1] * distance, x2, y2);
        canvas.drawPath(edgePath, edgePaint);

        float angle = arrowAngle(edge.toSide);
        float head = Ui.dp(getContext(), 6);
        edgePath.reset();
        edgePath.moveTo(x2, y2);
        edgePath.lineTo(x2 - head * (float) Math.cos(angle - 0.4), y2 - head * (float) Math.sin(angle - 0.4));
        edgePath.lineTo(x2 - head * (float) Math.cos(angle + 0.4), y2 - head * (float) Math.sin(angle + 0.4));
        edgePath.close();
        canvas.drawPath(edgePath, arrowPaint);

        if (edge.label != null && !edge.label.trim().isEmpty()) {
            float labelX = (x1 + x2) / 2f;
            float labelY = (y1 + y2) / 2f;
            String label = edge.label.trim();
            float textWidth = edgeLabelPaint.measureText(label);
            cardPaint.setColor(Ui.withAlpha(colorCard, 0.96f));
            RectF bubble = new RectF(labelX - textWidth / 2f - Ui.dp(getContext(), 6),
                    labelY - Ui.dp(getContext(), 14),
                    labelX + textWidth / 2f + Ui.dp(getContext(), 6),
                    labelY + Ui.dp(getContext(), 4));
            canvas.drawRoundRect(bubble, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), cardPaint);
            canvas.drawText(label, labelX, labelY, edgeLabelPaint);
        }
    }

    /** Where a line leaves a selected card side; imported cards fall back to geometry. */
    private float[] edgePoint(CanvasDoc.Node node, String side, float towardX, float towardY) {
        float worldScale = worldScale();
        float cx = node.centreX() * worldScale + offsetX;
        float cy = node.centreY() * worldScale + offsetY;
        float halfWidth = node.width * worldScale / 2f;
        float halfHeight = node.height * worldScale / 2f;
        if ("left".equals(side)) return new float[]{cx - halfWidth, cy};
        if ("right".equals(side)) return new float[]{cx + halfWidth, cy};
        if ("top".equals(side)) return new float[]{cx, cy - halfHeight};
        if ("bottom".equals(side)) return new float[]{cx, cy + halfHeight};
        float dx = towardX - cx;
        float dy = towardY - cy;
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) return new float[]{cx, cy};
        float scaleX = halfWidth / Math.max(0.001f, Math.abs(dx));
        float scaleY = halfHeight / Math.max(0.001f, Math.abs(dy));
        float t = Math.min(scaleX, scaleY);
        return new float[]{cx + dx * t, cy + dy * t};
    }

    private static float[] sideVector(String side) {
        if ("left".equals(side)) return new float[]{-1f, 0f};
        if ("top".equals(side)) return new float[]{0f, -1f};
        if ("bottom".equals(side)) return new float[]{0f, 1f};
        return new float[]{1f, 0f};
    }

    private static float arrowAngle(String targetSide) {
        if ("right".equals(targetSide)) return (float) Math.PI;
        if ("top".equals(targetSide)) return (float) (Math.PI / 2f);
        if ("bottom".equals(targetSide)) return (float) (-Math.PI / 2f);
        return 0f;
    }

    private void drawNode(Canvas canvas, CanvasDoc.Node node) {
        float worldScale = worldScale();
        float left = node.x * worldScale + offsetX;
        float top = node.y * worldScale + offsetY;
        float width = node.width * worldScale;
        float height = node.height * worldScale;
        if (left + width < 0 || left > getWidth() || top + height < 0 || top > getHeight()) {
            return;
        }
        cardRect.set(left, top, left + width, top + height);
        float radius = Ui.dp(getContext(), 10) * Math.min(1f, scale);

        if (node.isGroup()) {
            cardPaint.setColor(Ui.withAlpha(nodeFill(node), 0.12f));
            canvas.drawRoundRect(cardRect, radius, radius, cardPaint);
            cardStrokePaint.setColor(Ui.withAlpha(nodeAccent(node), 0.55f));
            cardStrokePaint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            canvas.drawRoundRect(cardRect, radius, radius,
                    isSelected(node) ? selectedStrokePaint : cardStrokePaint);
            cardStrokePaint.setColor(colorStroke);
            cardStrokePaint.setStrokeWidth(Ui.dp(getContext(), 1.2f));
            titlePaint.setColor(nodeAccent(node));
            titlePaint.setTextSize(Ui.sp(getContext(), 12f) * Math.max(0.7f, scale));
            canvas.drawText(node.label == null ? "" : node.label,
                    left + Ui.dp(getContext(), 11), top + Ui.dp(getContext(), 19), titlePaint);
            drawResizeHandle(canvas, node, left, top, width, height);
            return;
        }

        cardPaint.setColor(nodeFill(node));
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint);
        canvas.drawRoundRect(cardRect, radius, radius,
                isSelected(node) ? selectedStrokePaint : cardStrokePaint);

        if (node == selected && connectMode) {
            // Dashed emphasis while waiting for a connection target.
            selectedStrokePaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{Ui.dp(getContext(), 6), Ui.dp(getContext(), 4)}, 0));
            canvas.drawRoundRect(cardRect, radius, radius, selectedStrokePaint);
            selectedStrokePaint.setPathEffect(null);
        }

        drawResizeHandle(canvas, node, left, top, width, height);

        if (node.isFile() && isImage(node.file)) {
            Bitmap bitmap = images.get(node.file);
            if (bitmap == null) {
                requestImage(node.file);
            } else {
                float pad = Ui.dp(getContext(), 5);
                RectF available = new RectF(left + pad, top + pad,
                        left + width - pad, top + height - pad);
                float factor = Math.min(available.width() / bitmap.getWidth(),
                        available.height() / bitmap.getHeight());
                float drawWidth = bitmap.getWidth() * factor;
                float drawHeight = bitmap.getHeight() * factor;
                RectF destination = new RectF(
                        available.centerX() - drawWidth / 2f,
                        available.centerY() - drawHeight / 2f,
                        available.centerX() + drawWidth / 2f,
                        available.centerY() + drawHeight / 2f);
                canvas.save();
                canvas.clipRect(cardRect);
                canvas.drawBitmap(bitmap, null, destination, imagePaint);
                canvas.restore();
                return;
            }
        }

        // Text is only worth drawing once the card is big enough to read.
        if (width < Ui.dp(getContext(), 40) || height < Ui.dp(getContext(), 24)) return;

        float padding = Ui.dp(getContext(), 10) * Math.min(1f, Math.max(0.5f, scale));
        int textWidth = (int) Math.max(1, width - padding * 2);
        String content = node.isFile() ? fileTitle(node) : node.text;
        if (content.isEmpty()) content = node.isFile() ? "?" : "";

        TextPaint paint = node.isFile() ? titlePaint : textPaint;
        paint.setTextSize(Ui.sp(getContext(), node.isFile() ? 14f : 13f) * Math.min(1.3f, Math.max(0.6f, scale)));
        paint.setColor(node.isFile() ? colorAccent : colorText);

        StaticLayout layout = layoutFor(node, content, textWidth, paint);
        if (layout == null) return;
        canvas.save();
        canvas.clipRect(cardRect);
        canvas.translate(left + padding, top + padding);
        layout.draw(canvas);
        canvas.restore();

        if (!node.isFile() && node.note != null && !node.note.isEmpty()
                && height > Ui.dp(getContext(), 46)) {
            titlePaint.setColor(colorAccent);
            titlePaint.setTextSize(Ui.sp(getContext(), 10f) * Math.max(0.65f, scale));
            String title = titleResolver == null ? node.note : titleResolver.titleOf(node.note);
            canvas.drawText("↗ " + (title == null ? node.note : title), left + padding,
                    top + height - padding, titlePaint);
        }
    }

    private void drawResizeHandle(Canvas canvas, CanvasDoc.Node node,
                                  float left, float top, float width, float height) {
        if (node != selected || selectedNodes.size() != 1 || connectMode || drawingMode) return;
        float size = Ui.dp(getContext(), 10);
        cardPaint.setColor(colorAccent);
        canvas.drawRoundRect(new RectF(left + width - size, top + height - size,
                left + width + size / 3f, top + height + size / 3f),
                Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), cardPaint);
    }

    private int nodeFill(CanvasDoc.Node node) {
        if (node.color == null || node.color.isEmpty()) {
            return node.isFile() ? colorFileCard : colorCard;
        }
        return Ui.blend(colorCard, nodeAccent(node), 0.18f);
    }

    private int nodeAccent(CanvasDoc.Node node) {
        String color = node.color == null ? "" : node.color;
        switch (color) {
            case "1": return 0xFFE05252;
            case "2": return 0xFFF08C3A;
            case "3": return 0xFFD5A500;
            case "4": return 0xFF2F9E61;
            case "5": return 0xFF3388CC;
            case "6": return 0xFF805AD5;
            default:
                try { return android.graphics.Color.parseColor(color); }
                catch (Throwable ignored) { return colorAccent; }
        }
    }

    private static boolean isImage(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private void requestImage(final String reference) {
        if (!loadingImages.add(reference)) return;
        ImageLoader.load(reference, bitmap -> {
            loadingImages.remove(reference);
            if (bitmap != null) images.put(reference, bitmap);
            invalidate();
        });
    }

    private String fileTitle(CanvasDoc.Node node) {
        if (titleResolver != null) {
            String title = titleResolver.titleOf(node.file);
            if (title != null && !title.isEmpty()) return title;
        }
        return com.ccs.shard.core.NoteFile.titleFromPath(node.file);
    }

    private StaticLayout layoutFor(CanvasDoc.Node node, String text, int width, TextPaint paint) {
        CachedLayout cached = layouts.get(node.id);
        if (cached != null && cached.width == width && cached.text.equals(text)) {
            return cached.layout;
        }
        StaticLayout layout;
        try {
            layout = new StaticLayout(text, paint, width,
                    Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false);
        } catch (Throwable t) {
            return null;
        }
        layouts.put(node.id, new CachedLayout(text, width, layout));
        return layout;
    }

    /** Drops the cached layout for a node whose text changed. */
    public void invalidateNode(CanvasDoc.Node node) {
        if (node != null) layouts.remove(node.id);
        invalidate();
    }

    // ---------------------------------------------------------------- mutations

    /** Adds a text card and publishes one document edit. */
    public CanvasDoc.Node addTextNode(String text, float x, float y) {
        CanvasDoc.Node node = doc.addTextNode(text, x, y);
        select(node);
        invalidateNode(node);
        edited();
        return node;
    }

    /** Adds a vault-note card and publishes one document edit. */
    public CanvasDoc.Node addFileNode(String notePath, float x, float y) {
        CanvasDoc.Node node = doc.addFileNode(notePath, x, y);
        select(node);
        invalidateNode(node);
        edited();
        return node;
    }

    public CanvasDoc.Node addGroupNode(String label, float x, float y) {
        CanvasDoc.Node node = doc.addGroupNode(label, x, y);
        select(node);
        edited();
        return node;
    }

    /** Wraps the selected cards in a resizable visual group. */
    public CanvasDoc.Node groupSelection(String label) {
        if (selectedNodes.isEmpty()) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (CanvasDoc.Node node : selectedNodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.right());
            maxY = Math.max(maxY, node.bottom());
        }
        float padding = 28f;
        CanvasDoc.Node group = doc.addGroupNode(label, minX - padding, minY - padding);
        group.width = maxX - minX + padding * 2f;
        group.height = maxY - minY + padding * 2f;
        select(group);
        edited();
        return group;
    }

    /** Snaps all selected cards to the shared canvas grid and refreshes arrow sides. */
    public void alignSelection() {
        if (selectedNodes.isEmpty()) return;
        for (CanvasDoc.Node node : selectedNodes) {
            node.x = snapToGrid(node.x);
            node.y = snapToGrid(node.y);
            doc.refreshEdgeSidesFor(node);
            layouts.remove(node.id);
        }
        alignmentGuideX = Float.NaN;
        alignmentGuideY = Float.NaN;
        invalidate();
        edited();
    }

    /** Attaches every selected text card to the same existing vault note. */
    public void setNoteLinkForSelection(String noteId) {
        boolean changed = false;
        for (CanvasDoc.Node node : selectedNodes) {
            if (node.isFile() || node.isGroup()) continue;
            String value = noteId == null ? "" : noteId;
            if (!value.equals(node.note)) {
                node.note = value;
                changed = true;
            }
        }
        if (!changed) return;
        layouts.clear();
        invalidate();
        edited();
    }

    public void updateGroupNode(CanvasDoc.Node node, String label) {
        if (node == null || !node.isGroup()) return;
        node.label = label == null ? "" : label;
        invalidate();
        edited();
    }

    public void setNodeColor(CanvasDoc.Node node, String color) {
        if (node == null) return;
        node.color = color == null ? "" : color;
        invalidate();
        edited();
    }

    public void updateEdgeLabel(CanvasDoc.Edge edge, String label) {
        if (edge == null || !doc.edges().contains(edge)) return;
        edge.label = label == null ? "" : label;
        invalidate();
        edited();
    }

    public void updateTextNode(CanvasDoc.Node node, String text) {
        if (node == null || node.isFile() || !doc.nodes().contains(node)) return;
        String value = text == null ? "" : text;
        if (value.equals(node.text)) return;
        node.text = value;
        invalidateNode(node);
        edited();
    }

    public void removeNode(CanvasDoc.Node node) {
        if (node == null || !doc.nodes().contains(node)) return;
        doc.remove(node);
        layouts.remove(node.id);
        if (selectedNodes.remove(node)) {
            selected = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
            if (listener != null) listener.onSelectionChanged(selected);
        }
        invalidate();
        edited();
    }

    public void removeSelectedNodes() {
        if (selectedNodes.isEmpty()) return;
        List<CanvasDoc.Node> removing = new ArrayList<>(selectedNodes);
        for (CanvasDoc.Node node : removing) {
            doc.remove(node);
            layouts.remove(node.id);
        }
        clearSelection();
        edited();
    }

    public void clearDocument() {
        if (doc.isEmpty() && doc.edges().isEmpty() && doc.strokes().isEmpty()) return;
        doc.clear();
        layouts.clear();
        clearSelection();
        edited();
    }

    private void edited() {
        if (listener != null) listener.onDocumentEdited();
    }

    // ---------------------------------------------------------------- gestures

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        if (drawingMode) {
            if (event.getPointerCount() > 1 || scaleDetector.isInProgress()) {
                if (drawing != null) doc.removeStroke(drawing);
                drawing = null;
                invalidate();
                return true;
            }
            return handleDrawing(event) || handled;
        }
        handled = gestureDetector.onTouchEvent(event) || handled;
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (dragging != null || resizing != null) {
                if (dragging != null) {
                    for (CanvasDoc.Node node : selectedNodes) doc.refreshEdgeSidesFor(node);
                }
                dragging = null;
                resizing = null;
                alignmentGuideX = Float.NaN;
                alignmentGuideY = Float.NaN;
                invalidate();
                if (listener != null) listener.onDocumentEdited();
            }
        }
        return handled || super.onTouchEvent(event);
    }

    private boolean handleDrawing(MotionEvent event) {
        float worldX = toWorldX(event.getX());
        float worldY = toWorldY(event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawing = doc.addStroke(String.format(java.util.Locale.ROOT, "#%06X",
                        0xFFFFFF & colorAccent), 2.4f, worldX, worldY);
                lastDrawingX = worldX;
                lastDrawingY = worldY;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drawing == null) return true;
                float minimum = 0.7f / Math.max(MIN_SCALE, scale);
                if (Math.abs(worldX - lastDrawingX) >= minimum
                        || Math.abs(worldY - lastDrawingY) >= minimum) {
                    drawing.addPoint(worldX, worldY);
                    lastDrawingX = worldX;
                    lastDrawingY = worldY;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (drawing != null) {
                    drawing.addPoint(worldX, worldY);
                    if (drawing.pointCount() < 2) doc.removeStroke(drawing);
                    else edited();
                    drawing = null;
                    invalidate();
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (drawing != null) doc.removeStroke(drawing);
                drawing = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private CanvasDoc.Node nodeAt(float screenX, float screenY) {
        float worldX = toWorldX(screenX);
        float worldY = toWorldY(screenY);
        // Later nodes draw on top, so hit-test from the end.
        for (int i = doc.nodes().size() - 1; i >= 0; i--) {
            CanvasDoc.Node node = doc.nodes().get(i);
            if (node.isGroup()) continue;
            if (node.contains(worldX, worldY)) return node;
        }
        for (int i = doc.nodes().size() - 1; i >= 0; i--) {
            CanvasDoc.Node node = doc.nodes().get(i);
            if (node.isGroup() && node.contains(worldX, worldY)) return node;
        }
        return null;
    }

    private boolean resizeHandleHit(CanvasDoc.Node node, float screenX, float screenY) {
        if (node == null || selectedNodes.size() != 1) return false;
        float tolerance = 22f / Math.max(0.25f, scale);
        float worldX = toWorldX(screenX);
        float worldY = toWorldY(screenY);
        return Math.abs(worldX - node.right()) <= tolerance
                && Math.abs(worldY - node.bottom()) <= tolerance;
    }

    private void select(CanvasDoc.Node node) {
        selectedNodes.clear();
        selected = node;
        if (node != null) selectedNodes.add(node);
        if (node == null) connectMode = false;
        invalidate();
        if (listener != null) listener.onSelectionChanged(node);
    }

    private void addToSelection(CanvasDoc.Node node) {
        if (node == null) return;
        selectedNodes.add(node);
        selected = node;
        invalidate();
        if (listener != null) listener.onSelectionChanged(selected);
    }

    private void toggleSelection(CanvasDoc.Node node) {
        if (node == null) {
            clearSelection();
            return;
        }
        if (!selectedNodes.remove(node)) {
            selectedNodes.add(node);
            selected = node;
        } else if (selected == node) {
            selected = selectedNodes.isEmpty() ? null
                    : selectedNodes.iterator().next();
        }
        if (selected == null) connectMode = false;
        invalidate();
        if (listener != null) listener.onSelectionChanged(selected);
    }

    private void clearSelection() { select(null); }

    private boolean isSelected(CanvasDoc.Node node) { return selectedNodes.contains(node); }

    private static final float ALIGN_GRID = 48f;
    private static final float ALIGN_DISTANCE = 10f;

    private float snapToGrid(float value) { return Math.round(value / ALIGN_GRID) * ALIGN_GRID; }

    /** Finds nearby card edges/centres and shows a guide while the selection is dragged. */
    private float[] alignedPosition(CanvasDoc.Node moving, float proposedX, float proposedY) {
        alignmentGuideX = Float.NaN;
        alignmentGuideY = Float.NaN;
        float tolerance = ALIGN_DISTANCE / Math.max(MIN_SCALE, scale);
        float bestX = proposedX;
        float bestY = proposedY;
        float closestX = Float.MAX_VALUE;
        float closestY = Float.MAX_VALUE;
        float[] movingX = {proposedX, proposedX + moving.width / 2f, proposedX + moving.width};
        float[] movingY = {proposedY, proposedY + moving.height / 2f, proposedY + moving.height};
        for (CanvasDoc.Node other : doc.nodes()) {
            if (other == moving || selectedNodes.contains(other)) continue;
            float[] otherX = {other.x, other.centreX(), other.right()};
            float[] otherY = {other.y, other.centreY(), other.bottom()};
            for (float candidate : otherX) for (float current : movingX) {
                float delta = candidate - current;
                if (Math.abs(delta) <= tolerance && Math.abs(delta) < closestX) {
                    closestX = Math.abs(delta);
                    bestX = proposedX + delta;
                    alignmentGuideX = candidate;
                }
            }
            for (float candidate : otherY) for (float current : movingY) {
                float delta = candidate - current;
                if (Math.abs(delta) <= tolerance && Math.abs(delta) < closestY) {
                    closestY = Math.abs(delta);
                    bestY = proposedY + delta;
                    alignmentGuideY = candidate;
                }
            }
        }
        float gridX = snapToGrid(bestX);
        if (closestX == Float.MAX_VALUE && Math.abs(gridX - bestX) <= tolerance) {
            bestX = gridX;
            alignmentGuideX = gridX;
        }
        float gridY = snapToGrid(bestY);
        if (closestY == Float.MAX_VALUE && Math.abs(gridY - bestY) <= tolerance) {
            bestY = gridY;
            alignmentGuideY = gridY;
        }
        return new float[]{bestX, bestY};
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            if (resizeHandleHit(selected, event.getX(), event.getY())) {
                resizing = selected;
                dragging = null;
                return true;
            }
            CanvasDoc.Node node = nodeAt(event.getX(), event.getY());
            if (node != null) {
                dragging = node;
                dragOffsetX = toWorldX(event.getX()) - node.x;
                dragOffsetY = toWorldY(event.getY()) - node.y;
            } else {
                dragging = null;
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent down, MotionEvent move,
                                float distanceX, float distanceY) {
            if (dragging != null) {
                if (!isSelected(dragging)) {
                    if (multiSelectMode) addToSelection(dragging);
                    else select(dragging);
                }
                float proposedX = toWorldX(move.getX()) - dragOffsetX;
                float proposedY = toWorldY(move.getY()) - dragOffsetY;
                float[] aligned = alignedPosition(dragging, proposedX, proposedY);
                float deltaX = aligned[0] - dragging.x;
                float deltaY = aligned[1] - dragging.y;
                List<CanvasDoc.Node> moving = new ArrayList<>(selectedNodes);
                for (CanvasDoc.Node node : moving) {
                    node.x += deltaX;
                    node.y += deltaY;
                    if (!node.isGroup()) doc.bringToFront(node);
                    doc.refreshEdgeSidesFor(node);
                }
            } else if (resizing != null) {
                resizing.width = Math.max(80f, toWorldX(move.getX()) - resizing.x);
                resizing.height = Math.max(60f, toWorldY(move.getY()) - resizing.y);
                layouts.remove(resizing.id);
            } else {
                offsetX -= distanceX;
                offsetY -= distanceY;
            }
            invalidate();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent event) {
            CanvasView.this.performClick();
            CanvasDoc.Node node = nodeAt(event.getX(), event.getY());
            if (connectMode && selected != null && node != null && node != selected) {
                doc.toggleEdge(selected, node);
                connectMode = false;
                invalidate();
                if (listener != null) listener.onDocumentEdited();
                return true;
            }
            if (multiSelectMode) toggleSelection(node);
            else select(node);
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            CanvasDoc.Node node = nodeAt(event.getX(), event.getY());
            if (node != null) {
                if (listener != null) listener.onNodeActivated(node);
                return true;
            }
            if (listener != null) {
                listener.onEmptyMenu(toWorldX(event.getX()), toWorldY(event.getY()),
                        event.getX(), event.getY());
            }
            return true;
        }

        @Override
        public void onLongPress(MotionEvent event) {
            CanvasDoc.Node node = nodeAt(event.getX(), event.getY());
            Ui.hapticLongPress(CanvasView.this);
            if (node != null) {
                if (!isSelected(node)) {
                    if (multiSelectMode) addToSelection(node);
                    else select(node);
                }
                if (listener != null) {
                    listener.onNodeMenu(node, event.getX(), event.getY());
                }
            } else if (listener != null) {
                listener.onEmptyMenu(toWorldX(event.getX()), toWorldY(event.getY()),
                        event.getX(), event.getY());
            }
        }
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Zooming must not also drag whatever was under the first finger.
            dragging = null;
            resizing = null;
            zoomAround(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
            return true;
        }
    }
}
