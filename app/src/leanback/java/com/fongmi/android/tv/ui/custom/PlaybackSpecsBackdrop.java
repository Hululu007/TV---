package com.fongmi.android.tv.ui.custom;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.media3.ui.PlayerView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Low-resolution video-only backdrop. Never reads back the full-resolution window or the text. */
public final class PlaybackSpecsBackdrop {
    private static final long INTERVAL_MS = 350;
    private static final int MAX_SAMPLES = 14;
    private static final int MAX_WIDTH = 192;
    private static final int MAX_HEIGHT = 48;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "playback-specs-blur");
        thread.setDaemon(true);
        return thread;
    });

    private final JetStreamPlaybackSpecsView capsule;
    private final PlayerView player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable capture = this::capture;
    private Bitmap displayed;
    private int generation;
    private int samples;
    private int failures;
    private boolean running;
    private boolean inFlight;

    public PlaybackSpecsBackdrop(JetStreamPlaybackSpecsView capsule, PlayerView player) {
        this.capsule = capsule;
        this.player = player;
    }

    public void start() {
        stop();
        running = true;
        samples = 0;
        failures = 0;
        main.postDelayed(capture, 120);
    }

    public void stop() {
        generation++;
        running = false;
        main.removeCallbacks(capture);
        capsule.setBackdrop(null);
        // Let RenderThread release the previously recorded bitmap before it is collected.
        // Explicit recycling can race a hardware display list that is still being consumed.
        displayed = null;
    }

    private boolean visible() {
        return running && capsule.isActive() && capsule.isShown() && capsule.isAttachedToWindow();
    }

    private void capture() {
        if (!visible() || samples >= MAX_SAMPLES || failures >= 2) {
            stop();
            return;
        }
        if (inFlight) {
            main.postDelayed(capture, INTERVAL_MS);
            return;
        }
        View surface = player.getVideoSurfaceView();
        if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0 || capsule.getWidth() <= 0) {
            failures++;
            main.postDelayed(capture, INTERVAL_MS);
            return;
        }
        Rect crop = cropInSurface(capsule, surface);
        // Letterbox areas have no video to blur. Keep the neutral glass there.
        if (crop == null) {
            stop();
            return;
        }
        int token = generation;
        samples++;
        inFlight = true;
        Bitmap bitmap = null;
        try {
            if (surface instanceof SurfaceView surfaceView && Build.VERSION.SDK_INT >= 26) {
                bitmap = Bitmap.createBitmap(Math.min(MAX_WIDTH, crop.width()),
                        Math.min(MAX_HEIGHT, crop.height()), Bitmap.Config.ARGB_8888);
                Bitmap target = bitmap;
                Rect frame = surfaceView.getHolder().getSurfaceFrame();
                if (frame.isEmpty()) throw new IllegalStateException("Video surface has no buffer");
                Rect bufferCrop = new Rect(crop.left * frame.width() / surface.getWidth(),
                        crop.top * frame.height() / surface.getHeight(),
                        (int) Math.ceil(crop.right * (double) frame.width() / surface.getWidth()),
                        (int) Math.ceil(crop.bottom * (double) frame.height() / surface.getHeight()));
                PixelCopy.request(surfaceView, bufferCrop, target,
                        result -> copied(token, surface, target, null, result), main);
            } else if (surface instanceof SurfaceView || surface instanceof TextureView) {
                // API 24/25 has no rectangular SurfaceView copy. TextureView.getBitmap also only
                // captures its whole surface. Bound that temporary image to 320 x 180 pixels.
                float scale = Math.min(1f, Math.min(320f / surface.getWidth(), 180f / surface.getHeight()));
                int width = Math.max(1, Math.round(surface.getWidth() * scale));
                int height = Math.max(1, Math.round(surface.getHeight() * scale));
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Rect smallCrop = new Rect(Math.max(0, crop.left * width / surface.getWidth()),
                        Math.max(0, crop.top * height / surface.getHeight()),
                        Math.min(width, (int) Math.ceil(crop.right * (double) width / surface.getWidth())),
                        Math.min(height, (int) Math.ceil(crop.bottom * (double) height / surface.getHeight())));
                Bitmap target = bitmap;
                if (surface instanceof SurfaceView surfaceView) {
                    PixelCopy.request(surfaceView, target,
                            result -> copied(token, surface, target, smallCrop, result), main);
                } else {
                    Bitmap result = ((TextureView) surface).getBitmap(target);
                    copied(token, surface, target, smallCrop,
                            result == null ? PixelCopy.ERROR_SOURCE_NO_DATA : PixelCopy.SUCCESS);
                }
            } else {
                inFlight = false;
                stop();
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (bitmap != null) bitmap.recycle();
            inFlight = false;
            failures++;
            if (visible()) main.postDelayed(capture, INTERVAL_MS);
        }
    }

    private void copied(int token, View surface, Bitmap bitmap, Rect crop, int result) {
        if (token != generation || !visible() || surface != player.getVideoSurfaceView()) {
            bitmap.recycle();
            inFlight = false;
            return;
        }
        if (result != PixelCopy.SUCCESS) {
            bitmap.recycle();
            inFlight = false;
            failures++;
            main.postDelayed(capture, INTERVAL_MS);
            return;
        }
        WORKER.execute(() -> {
            Bitmap blurred = bitmap;
            try {
                if (crop != null) {
                    blurred = Bitmap.createBitmap(Math.max(1, Math.min(MAX_WIDTH, crop.width())),
                            Math.max(1, Math.min(MAX_HEIGHT, crop.height())), Bitmap.Config.ARGB_8888);
                    new Canvas(blurred).drawBitmap(bitmap, crop,
                            new Rect(0, 0, blurred.getWidth(), blurred.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
                    bitmap.recycle();
                }
                gaussianBlur(blurred);
                Bitmap ready = blurred;
                main.post(() -> {
                    inFlight = false;
                    if (token != generation || !visible() || surface != player.getVideoSurfaceView()) {
                        ready.recycle();
                        return;
                    }
                    failures = 0;
                    displayed = ready;
                    capsule.setBackdrop(displayed);
                    main.postDelayed(capture, INTERVAL_MS);
                });
            } catch (RuntimeException failure) {
                // Neither image has reached a display list yet, so both are safe to release here.
                if (!blurred.isRecycled()) blurred.recycle();
                if (!bitmap.isRecycled()) bitmap.recycle();
                main.post(() -> {
                    inFlight = false;
                    if (token != generation || !visible()) return;
                    failures++;
                    main.postDelayed(capture, INTERVAL_MS);
                });
            }
        });
    }

    /** Returns only fully covered video regions: never stretch a partial crop over letterboxing. */
    static Rect cropInSurface(View capsule, View surface) {
        int[] capsuleLocation = new int[2];
        int[] surfaceLocation = new int[2];
        capsule.getLocationInWindow(capsuleLocation);
        surface.getLocationInWindow(surfaceLocation);
        int left = capsuleLocation[0] - surfaceLocation[0];
        int top = capsuleLocation[1] - surfaceLocation[1];
        Rect crop = new Rect(left, top, left + capsule.getWidth(), top + capsule.getHeight());
        return new Rect(0, 0, surface.getWidth(), surface.getHeight()).contains(crop) ? crop : null;
    }

    /** Separable 13-tap Gaussian (sigma 3) on at most 192 x 48 pixels, always off the UI thread. */
    static void gaussianBlur(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] input = new int[width * height];
        int[] output = new int[input.length];
        bitmap.getPixels(input, 0, width, 0, 0, width, height);
        float[] kernel = new float[13];
        float total = 0;
        for (int i = -6; i <= 6; i++) total += kernel[i + 6] = (float) Math.exp(-i * i / 18.0);
        for (int i = 0; i < kernel.length; i++) kernel[i] /= total;
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float r = 0, g = 0, b = 0;
                    for (int i = -6; i <= 6; i++) {
                        int xx = pass == 0 ? Math.max(0, Math.min(width - 1, x + i)) : x;
                        int yy = pass == 1 ? Math.max(0, Math.min(height - 1, y + i)) : y;
                        int pixel = input[yy * width + xx];
                        float weight = kernel[i + 6];
                        r += ((pixel >> 16) & 255) * weight;
                        g += ((pixel >> 8) & 255) * weight;
                        b += (pixel & 255) * weight;
                    }
                    output[y * width + x] = 0xFF000000 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
                }
            }
            int[] swap = input;
            input = output;
            output = swap;
        }
        bitmap.setPixels(input, 0, width, 0, 0, width, height);
    }
}
