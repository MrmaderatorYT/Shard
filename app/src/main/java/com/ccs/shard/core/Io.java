package com.ccs.shard.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central threading for the app.
 *
 * <p>Deliberately tiny: one serial disk executor (so file writes can never race
 * each other), one small compute pool sized for low-end hardware, and the main
 * handler. On a 4-core Exynos 850 (Galaxy A13) spawning threads per operation
 * was the single biggest source of jank, so nothing here allocates threads
 * lazily beyond these fixed pools.
 */
public final class Io {

    private Io() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Serial executor for all vault file IO. Ordering guarantees atomicity of saves. */
    private static final ThreadPoolExecutor DISK = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            named("shard-disk"));

    /** Small pool for parsing / layout / search. Capped at 2 to stay friendly on 4-core budget SoCs. */
    private static final ThreadPoolExecutor COMPUTE = new ThreadPoolExecutor(
            1, 2, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            named("shard-compute"));

    static {
        DISK.allowCoreThreadTimeOut(true);
        COMPUTE.allowCoreThreadTimeOut(true);
    }

    private static ThreadFactory named(final String prefix) {
        final AtomicInteger counter = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };
    }

    public static Executor disk() { return DISK; }

    public static Executor compute() { return COMPUTE; }

    public static void onDisk(Runnable r) { DISK.execute(r); }

    public static void onCompute(Runnable r) { COMPUTE.execute(r); }

    /**
     * Queues cancellable CPU work.  Callers that render UI data can cancel an
     * obsolete request when newer input arrives without creating another
     * short-lived thread.
     */
    public static Future<?> submitCompute(Runnable r) { return COMPUTE.submit(r); }

    public static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    public static void onMainDelayed(Runnable r, long delayMs) {
        MAIN.postDelayed(r, delayMs);
    }

    public static void cancelMain(Runnable r) {
        MAIN.removeCallbacks(r);
    }

    public static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** Runs {@code work} off the main thread, then delivers its result on the main thread. */
    public static <T> void load(final Task<T> work, final Result<T> onDone) {
        DISK.execute(new Runnable() {
            @Override
            public void run() {
                T value = null;
                Throwable error = null;
                try {
                    value = work.run();
                } catch (Throwable t) {
                    error = t;
                }
                final T fValue = value;
                final Throwable fError = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fError != null) onDone.onError(fError);
                        else onDone.onReady(fValue);
                    }
                });
            }
        });
    }

    public interface Task<T> {
        T run() throws Exception;
    }

    public interface Result<T> {
        void onReady(T value);
        /** Default behaviour is to log; override when the UI must react. */
        void onError(Throwable t);
    }

    /** Convenience base so callers only implement the happy path. */
    public abstract static class Ok<T> implements Result<T> {
        @Override
        public void onError(Throwable t) {
            android.util.Log.w("Shard", "background task failed", t);
        }
    }
}
