package com.ccs.shard.block;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import com.ccs.shard.core.Io;
import com.ccs.shard.core.VaultRepository;

import java.io.File;

/**
 * Loads note images off the main thread, downsampled to the display.
 *
 * <p>Sized for a low-memory device: the cache is a fraction of the heap rather
 * than a fixed byte count, and every bitmap is decoded at most to the screen
 * width. Loading a 12-megapixel phone photo at full size is the fastest way to
 * OOM a 3 GB device, so {@code inSampleSize} is always computed first.
 */
public final class ImageLoader {

    public interface Callback { void onLoaded(Bitmap bitmap); }

    private ImageLoader() {}

    private static final int MAX_EDGE_PX = 1600;

    private static volatile VaultRepository repository;
    private static volatile int screenWidth = 1080;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(cacheSizeBytes()) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static int cacheSizeBytes() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        // An eighth of the heap, clamped so tiny devices keep room to edit.
        long size = maxHeap / 8;
        return (int) Math.max(2L * 1024 * 1024, Math.min(size, 24L * 1024 * 1024));
    }

    public static void init(VaultRepository repo, int displayWidthPx) {
        repository = repo;
        if (displayWidthPx > 0) screenWidth = displayWidthPx;
    }

    public static void clear() {
        CACHE.evictAll();
    }

    /** Loads {@code reference} into {@code target}, using the cache when possible. */
    public static void load(final ImageView target, final String reference) {
        if (reference == null || reference.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(reference);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        target.setTag(reference);
        Io.load(new Io.Task<Bitmap>() {
            @Override public Bitmap run() {
                return decode(reference);
            }
        }, new Io.Ok<Bitmap>() {
            @Override public void onReady(Bitmap value) {
                if (value == null) return;
                CACHE.put(reference, value);
                // The holder may have been recycled onto another image by now.
                if (reference.equals(target.getTag())) target.setImageBitmap(value);
            }
        });
    }

    /** Loads into a custom renderer such as CanvasView. Callback runs on the main thread. */
    public static void load(final String reference, final Callback callback) {
        if (reference == null || reference.isEmpty() || callback == null) return;
        Bitmap cached = CACHE.get(reference);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        Io.load(new Io.Task<Bitmap>() {
            @Override public Bitmap run() { return decode(reference); }
        }, new Io.Ok<Bitmap>() {
            @Override public void onReady(Bitmap value) {
                if (value != null) CACHE.put(reference, value);
                callback.onLoaded(value);
            }
        });
    }

    private static Bitmap decode(String reference) {
        VaultRepository repo = repository;
        if (repo == null) return null;
        File file = repo.resolveAttachment(reference);
        if (file == null || !file.exists()) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int targetWidth = Math.min(screenWidth, MAX_EDGE_PX);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        // RGB_565 halves the memory per image; note screenshots and photos do not
        // visibly suffer, and it keeps long notes with many images affordable.
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            CACHE.evictAll();
            return null;
        }
    }
}
