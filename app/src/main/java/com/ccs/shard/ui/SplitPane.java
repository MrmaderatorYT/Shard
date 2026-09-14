package com.ccs.shard.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * Holds two panes with a divider the user can drag to rebalance them.
 *
 * <p>Takes exactly two children from XML and inserts its own divider between
 * them. The split axis follows the available space: side by side when there is
 * room for two readable columns, stacked otherwise, so the same layout works on
 * a phone in portrait and a tablet in landscape without a separate resource
 * qualifier.
 *
 * <p>The ratio is reported through {@link #setOnRatioChanged} so the host can
 * persist it; a split the user has adjusted should still be adjusted next time.
 */
public final class SplitPane extends LinearLayout {

    /** Below this width there is no room for two columns, so panes stack. */
    private static final int SIDE_BY_SIDE_MIN_DP = 560;
    /** Neither pane may be squeezed below this fraction of the whole. */
    private static final float MIN_RATIO = 0.18f;
    private static final float MAX_RATIO = 0.82f;

    public interface OnRatioChanged {
        void onRatioChanged(float ratio);
    }

    public interface OnDragStateChanged {
        void onDragStateChanged(boolean dragging);
    }

    private View first;
    private View second;
    private View divider;

    private float ratio = 0.5f;
    private boolean secondVisible;
    private boolean dragging;
    private float dragStart;
    private float ratioAtDragStart;
    private final int touchSlop;
    private OnRatioChanged onRatioChanged;
    private OnDragStateChanged onDragStateChanged;

    public SplitPane(Context context) {
        this(context, null);
    }

    public SplitPane(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(HORIZONTAL);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() < 2) {
            throw new IllegalStateException("SplitPane needs exactly two children");
        }
        first = getChildAt(0);
        second = getChildAt(1);
        divider = createDivider();
        addView(divider, 1);
        applyRatio();
        setSecondVisible(false);
    }

    private View createDivider() {
        View view = new View(getContext());
        int outline = Ui.themeColor(getContext(),
                com.google.android.material.R.attr.colorOutlineVariant, 0x33808080);
        view.setBackgroundColor(outline);
        view.setContentDescription(getContext().getString(
                com.ccs.shard.R.string.split_divider));
        return view;
    }

    // ---------------------------------------------------------------- geometry

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int widthDp = Math.round(MeasureSpec.getSize(widthSpec)
                / getResources().getDisplayMetrics().density);
        int wanted = widthDp >= SIDE_BY_SIDE_MIN_DP ? HORIZONTAL : VERTICAL;
        if (getOrientation() != wanted) {
            // Changing orientation re-lays out once more; guarded so it settles.
            setOrientation(wanted);
            applyRatio();
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    /** Splits the space between the panes according to the current ratio. */
    private void applyRatio() {
        if (first == null || second == null) return;
        boolean horizontal = getOrientation() == HORIZONTAL;
        int thickness = Ui.dp(getContext(), secondVisible ? 10 : 0);

        LayoutParams firstParams = (LayoutParams) first.getLayoutParams();
        LayoutParams secondParams = (LayoutParams) second.getLayoutParams();
        LayoutParams dividerParams = (LayoutParams) divider.getLayoutParams();

        if (horizontal) {
            firstParams.width = 0;
            firstParams.height = LayoutParams.MATCH_PARENT;
            secondParams.width = 0;
            secondParams.height = LayoutParams.MATCH_PARENT;
            dividerParams.width = thickness;
            dividerParams.height = LayoutParams.MATCH_PARENT;
        } else {
            firstParams.width = LayoutParams.MATCH_PARENT;
            firstParams.height = 0;
            secondParams.width = LayoutParams.MATCH_PARENT;
            secondParams.height = 0;
            dividerParams.width = LayoutParams.MATCH_PARENT;
            dividerParams.height = thickness;
        }
        firstParams.weight = secondVisible ? ratio : 1f;
        secondParams.weight = secondVisible ? 1f - ratio : 0f;
        dividerParams.weight = 0f;

        first.setLayoutParams(firstParams);
        second.setLayoutParams(secondParams);
        divider.setLayoutParams(dividerParams);
    }

    // ---------------------------------------------------------------- api

    public void setSecondVisible(boolean visible) {
        secondVisible = visible;
        second.setVisibility(visible ? VISIBLE : GONE);
        divider.setVisibility(visible ? VISIBLE : GONE);
        applyRatio();
        requestLayout();
    }

    public boolean isSecondVisible() { return secondVisible; }

    /** True while the divider is being manipulated by the user. */
    public boolean isDragging() { return dragging; }

    /** Fraction of the container given to the first pane, clamped to sane bounds. */
    public void setRatio(float value) {
        ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, value));
        applyRatio();
        requestLayout();
    }

    public float ratio() { return ratio; }

    public void setOnRatioChanged(OnRatioChanged listener) {
        this.onRatioChanged = listener;
    }

    public void setOnDragStateChanged(OnDragStateChanged listener) {
        this.onDragStateChanged = listener;
    }

    // ---------------------------------------------------------------- dragging

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!secondVisible) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && onDivider(event)) {
            beginDrag(event);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!secondVisible) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!onDivider(event)) return super.onTouchEvent(event);
                beginDrag(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return super.onTouchEvent(event);
                float extent = axisExtent();
                if (extent <= 0) return true;
                float delta = axisPosition(event) - dragStart;
                if (Math.abs(delta) < touchSlop) return true;
                // Keep the expensive pane children at their original widths
                // while the finger moves. Translating only the divider gives
                // immediate visual feedback without forcing two large text
                // views through measure/layout for every MotionEvent.
                updateDragPosition(delta, extent);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    divider.setTranslationX(0f);
                    divider.setTranslationY(0f);
                    applyRatio();
                    requestLayout();
                    if (onDragStateChanged != null) {
                        onDragStateChanged.onDragStateChanged(false);
                    }
                    if (onRatioChanged != null) onRatioChanged.onRatioChanged(ratio);
                    return true;
                }
                return super.onTouchEvent(event);
            default:
                return super.onTouchEvent(event);
        }
    }

    /** True when the touch is on the divider, with a generous grab margin. */
    private boolean onDivider(MotionEvent event) {
        if (divider.getVisibility() != VISIBLE) return false;
        int grab = Ui.dp(getContext(), 12);
        if (getOrientation() == HORIZONTAL) {
            return event.getX() >= divider.getLeft() - grab
                    && event.getX() <= divider.getRight() + grab;
        }
        return event.getY() >= divider.getTop() - grab
                && event.getY() <= divider.getBottom() + grab;
    }

    private float axisPosition(MotionEvent event) {
        return getOrientation() == HORIZONTAL ? event.getX() : event.getY();
    }

    private float axisExtent() {
        return getOrientation() == HORIZONTAL ? getWidth() : getHeight();
    }

    private void beginDrag(MotionEvent event) {
        if (dragging) return;
        dragging = true;
        dragStart = axisPosition(event);
        ratioAtDragStart = ratio;
        divider.setTranslationX(0f);
        divider.setTranslationY(0f);
        if (onDragStateChanged != null) onDragStateChanged.onDragStateChanged(true);
    }

    private void updateDragPosition(float delta, float extent) {
        float nextRatio = Math.max(MIN_RATIO,
                Math.min(MAX_RATIO, ratioAtDragStart + delta / extent));
        ratio = nextRatio;
        float effectiveDelta = (nextRatio - ratioAtDragStart) * extent;
        if (getOrientation() == HORIZONTAL) {
            divider.setTranslationX(effectiveDelta);
        } else {
            divider.setTranslationY(effectiveDelta);
        }
    }
}
