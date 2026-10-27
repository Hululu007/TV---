package com.fongmi.android.tv.ui.custom;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.ui.PlayerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.fongmi.android.tv.test.NativeUiEvidence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exercises real Android surface readback with a synthetic scene; no renderer or network mocks. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackSpecsBackdropTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private CorePlaybackActivity activity;
    private PlayerView player;
    private JetStreamPlaybackSpecsView capsule;
    private PlaybackSpecsBackdrop backdrop;
    private CountDownLatch releaseWorker;

    @Before public void setup() {
        assertEquals("com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @After public void cleanup() {
        if (releaseWorker != null) releaseWorker.countDown();
        main(() -> {
            if (backdrop != null) backdrop.stop();
            if (capsule != null) capsule.dismiss();
            if (activity != null) activity.finish();
        });
        instrumentation.waitForIdleSync();
    }

    @Test public void shouldReadFixedSizeSurfaceBufferAndDiscardCancelledResults() throws Exception {
        attach(false);
        SurfaceView surface = value(() -> (SurfaceView) player.getVideoSurfaceView());
        main(() -> surface.getHolder().setFixedSize(320, 180));
        await(() -> surface.getHolder().getSurface().isValid()
                && surface.getHolder().getSurfaceFrame().width() == 320
                && surface.getHolder().getSurfaceFrame().height() == 180, "fixed video buffer");
        assertNotEquals("Exercise buffer coordinates distinct from view coordinates", 320, value(surface::getWidth).intValue());
        paintSurface(surface, false);
        startScene();
        await(() -> displayed() != null, "SurfaceView PixelCopy and worker blur");
        Bitmap first = value(this::displayed);
        assertBoundedBlurredEdge(first);
        NativeUiEvidence.capture("playback-specs-surface-glass");
        main(backdrop::stop);
        assertFalse("A bitmap that reached RenderThread must not be recycled eagerly", first.isRecycled());

        // Hold the real blur executor so cancellation races with a captured but unpublished frame.
        releaseWorker = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        ExecutorService worker = (ExecutorService) field(PlaybackSpecsBackdrop.class, "WORKER");
        worker.execute(() -> {
            blocked.countDown();
            try { releaseWorker.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue("Blur worker barrier", blocked.await(2, TimeUnit.SECONDS));
        main(() -> {
            capsule.show("1080P SDR", "H.264 8bit", "AAC", "2.0", null);
            backdrop.start();
        });
        await(() -> (boolean) field(backdrop, "inFlight"), "pending surface capture");
        main(backdrop::stop);
        paintSurface(surface, true);
        main(backdrop::start);
        SystemClock.sleep(650);
        main(() -> {
            assertNull("Cancelled data cannot reach the new presentation", displayed());
            // Cancellation can finish before PixelCopy has queued its worker task. In that case
            // the replacement may already occupy the single flight while the worker is blocked.
            assertTrue("Keep at most one readback or blur in flight", (int) field(backdrop, "samples") <= 1);
        });
        releaseWorker.countDown();
        await(() -> displayed() != null, "replacement surface scene");
        Bitmap replacement = value(this::displayed);
        int pixel = replacement.getPixel(replacement.getWidth() / 2, replacement.getHeight() / 2);
        assertTrue("Only the new green scene may be published", Color.green(pixel) > 240
                && Color.red(pixel) < 10 && Color.blue(pixel) < 10);
        assertStoppedSampling();
    }

    @Test public void shouldReadTextureViewThroughABoundedTemporaryAndClearBorrowedPixels() {
        attach(true);
        TextureView surface = value(() -> (TextureView) player.getVideoSurfaceView());
        await(surface::isAvailable, "TextureView producer");
        main(() -> {
            Rect crop = PlaybackSpecsBackdrop.cropInSurface(capsule, surface);
            assertNotNull(crop);
            Canvas canvas = surface.lockCanvas();
            assertNotNull(canvas);
            try { paintEdge(canvas, crop.exactCenterX()); }
            finally { surface.unlockCanvasAndPost(canvas); }
        });
        SystemClock.sleep(150);
        startScene();
        await(() -> displayed() != null, "TextureView crop and worker blur");
        Bitmap captured = value(this::displayed);
        assertBoundedBlurredEdge(captured);
        main(() -> {
            assertTrue("Texture readback uses a whole-frame image no wider than 320 pixels",
                    captured.getWidth() <= Math.ceil(capsule.getWidth() * 320.0 / surface.getWidth()) + 1);
            assertTrue("Texture readback uses a whole-frame image no taller than 180 pixels",
                    captured.getHeight() <= Math.ceil(capsule.getHeight() * 180.0 / surface.getHeight()) + 1);
        });
        NativeUiEvidence.capture("playback-specs-texture-glass");
        assertStoppedSampling();
    }

    @Test public void shouldKeepNeutralGlassWhenThePillCrossesALetterboxBoundary() {
        attach(false);
        main(() -> {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) capsule.getLayoutParams();
            params.leftMargin = -dp(12);
            capsule.setLayoutParams(params);
        });
        await(() -> capsule.getLeft() < 0, "pill outside video bounds");
        main(() -> {
            assertNull(PlaybackSpecsBackdrop.cropInSurface(capsule, player.getVideoSurfaceView()));
            backdrop.start();
        });
        await(() -> !(boolean) field(backdrop, "running"), "neutral glass fallback");
        main(() -> {
            assertTrue("Missing video pixels must not hide the useful specifications", capsule.isActive());
            assertNull(displayed());
            assertNull(field(capsule, "backdrop"));
            assertEquals("Do not read back uncovered video", 0, field(backdrop, "samples"));
        });
    }

    @Test public void shouldPreserveFlatColorsAndSoftenAnEdgeWithoutColorBias() {
        Bitmap bitmap = Bitmap.createBitmap(41, 21, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.rgb(53, 106, 159));
            PlaybackSpecsBackdrop.gaussianBlur(bitmap);
            assertEquals("Flat video colors stay flat, including image edges", Color.rgb(53, 106, 159), bitmap.getPixel(0, 0));
            assertEquals(bitmap.getPixel(0, 0), bitmap.getPixel(20, 10));
            paintEdge(new Canvas(bitmap), 20.5f);
            PlaybackSpecsBackdrop.gaussianBlur(bitmap);
            int left = bitmap.getPixel(2, 10), center = bitmap.getPixel(20, 10), right = bitmap.getPixel(38, 10);
            assertEquals(Color.RED, left);
            assertEquals(Color.BLUE, right);
            assertTrue("The Gaussian mixes both sides at the boundary", Color.red(center) > 80
                    && Color.red(center) < 180 && Color.blue(center) > 80 && Color.blue(center) < 180);
            assertEquals("No new green channel", 0, Color.green(center));
            assertEquals("Video output stays opaque", 255, Color.alpha(center));
        } finally {
            bitmap.recycle();
        }
    }

    private void attach(boolean texture) {
        main(() -> {
            FrameLayout host = new FrameLayout(activity);
            player = texture ? (PlayerView) activity.getLayoutInflater().inflate(R.layout.view_wall_video, host, false)
                    : new PlayerView(activity);
            player.setUseController(false);
            player.setShutterBackgroundColor(Color.TRANSPARENT);
            host.addView(player, new FrameLayout.LayoutParams(-1, -1));
            capsule = new JetStreamPlaybackSpecsView(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
            params.setMargins(dp(32), dp(32), dp(32), 0);
            host.addView(capsule, params);
            activity.setContentView(host);
            backdrop = new PlaybackSpecsBackdrop(capsule, player);
            capsule.show("1080P SDR", "H.264 8bit", "AAC", "2.0", null);
        });
        await(() -> capsule.getWidth() > 0 && !capsule.isLayoutRequested()
                && player.getVideoSurfaceView().getWidth() > 0, "video and capsule layout");
    }

    private void paintSurface(SurfaceView surface, boolean replacement) {
        main(() -> {
            Canvas canvas = surface.getHolder().lockCanvas();
            assertNotNull(canvas);
            try {
                if (replacement) canvas.drawColor(Color.GREEN);
                else {
                    Rect crop = PlaybackSpecsBackdrop.cropInSurface(capsule, surface);
                    assertNotNull(crop);
                    paintEdge(canvas, crop.exactCenterX() * canvas.getWidth() / surface.getWidth());
                }
            } finally {
                surface.getHolder().unlockCanvasAndPost(canvas);
            }
        });
    }

    private void startScene() {
        main(() -> {
            // Surface creation can be slow on a cold emulator; start the hold only after readiness.
            capsule.show("1080P SDR", "H.264 8bit", "AAC", "2.0", null);
            backdrop.start();
        });
    }

    private void paintEdge(Canvas canvas, float middle) {
        canvas.drawColor(Color.RED);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawRect(middle, 0, canvas.getWidth(), canvas.getHeight(), paint);
    }

    private void assertBoundedBlurredEdge(Bitmap bitmap) {
        assertTrue(bitmap.getWidth() > 1 && bitmap.getWidth() <= 192);
        assertTrue(bitmap.getHeight() > 0 && bitmap.getHeight() <= 48);
        int y = bitmap.getHeight() / 2;
        int left = bitmap.getPixel(bitmap.getWidth() / 8, y);
        int right = bitmap.getPixel(bitmap.getWidth() * 7 / 8, y);
        assertTrue("Crop starts on the red side", Color.red(left) > 210 && Color.blue(left) < 40);
        assertTrue("Crop ends on the blue side", Color.blue(right) > 210 && Color.red(right) < 40);
        boolean mixed = false;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            int pixel = bitmap.getPixel(x, y);
            if (Color.red(pixel) > 20 && Color.blue(pixel) > 20) mixed = true;
        }
        assertTrue("Real readback reaches the Gaussian blur", mixed);
    }

    private void assertStoppedSampling() {
        main(backdrop::stop);
        await(() -> !(boolean) field(backdrop, "inFlight"), "capture completion after cancellation");
        int count = value(() -> (int) field(backdrop, "samples"));
        SystemClock.sleep(500);
        main(() -> {
            assertNull(displayed());
            assertNull("Release the view's borrowed image", field(capsule, "backdrop"));
            assertEquals("No timer may request another capture after stop", count, field(backdrop, "samples"));
        });
    }

    private Bitmap displayed() { return (Bitmap) field(backdrop, "displayed"); }

    private Object field(Object target, String name) {
        try {
            Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 2500;
        do {
            if (value(condition::getAsBoolean)) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Timed out: " + message);
    }

    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Backdrop fixture failed", failure.get());
    }

    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        main(() -> result.set(action.get()));
        return result.get();
    }
}
