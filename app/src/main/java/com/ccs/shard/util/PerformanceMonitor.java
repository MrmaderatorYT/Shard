package com.ccs.shard.util;

import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/** Lightweight startup and scroll profiler visible in logcat as ShardPerf. */
public final class PerformanceMonitor {

    private static final String TAG = "ShardPerf";
    private static final long PROCESS_START = SystemClock.elapsedRealtime();

    private PerformanceMonitor() {}

    public static void reportFirstFrame(View root, String screen) {
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(() -> Log.i(TAG, screen + " first frame: "
                        + (SystemClock.elapsedRealtime() - PROCESS_START) + " ms"));
                return true;
            }
        });
    }

    public static void profileScrolling(RecyclerView list, String screen) {
        FrameSampler sampler = new FrameSampler(screen);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView recyclerView, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) sampler.stop();
                else if (state == RecyclerView.SCROLL_STATE_DRAGGING) sampler.start();
            }
        });
    }

    private static final class FrameSampler implements Choreographer.FrameCallback {
        private final String label;
        private boolean running;
        private long previous;
        private int frames;
        private int janky;
        private float worstMs;

        FrameSampler(String label) { this.label = label; }

        void start() {
            if (running) return;
            running = true;
            previous = 0L;
            frames = 0;
            janky = 0;
            worstMs = 0f;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void stop() {
            if (!running) return;
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
            Log.i(TAG, label + " scroll: " + frames + " frames, " + janky
                    + " janky, worst " + String.format(java.util.Locale.US, "%.1f", worstMs) + " ms");
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (previous != 0L) {
                float ms = (frameTimeNanos - previous) / 1_000_000f;
                frames++;
                if (ms > 24f) janky++;
                worstMs = Math.max(worstMs, ms);
            }
            previous = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
