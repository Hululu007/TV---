package com.fongmi.android.tv.ui;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.FrameMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.custom.JetStreamPlaybackSpecsView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Attached native capsule: actual platform motion, clipping, lifecycle timers and remote focus. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackSpecsCapsuleTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private CorePlaybackActivity activity;
    private FrameLayout host;
    private Button focusTarget;
    private JetStreamPlaybackSpecsView capsule;
    private String originalMotionScale;
    private boolean motionScaleSaved;

    @Before public void setup() {
        assertEquals("Use the isolated TV preview", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        assertEquals("TV capsule", "leanback", BuildConfig.FLAVOR_mode);
        originalMotionScale = Settings.Global.getString(instrumentation.getTargetContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE);
        motionScaleSaved = true;
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            host = new FrameLayout(activity);
            host.setBackgroundColor(Color.rgb(28, 42, 58));
            focusTarget = new Button(activity);
            focusTarget.setText("Playback focus stays here");
            focusTarget.setFocusableInTouchMode(true);
            FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(dp(280), dp(48), Gravity.BOTTOM | Gravity.LEFT);
            buttonParams.setMargins(dp(32), 0, 0, dp(32));
            host.addView(focusTarget, buttonParams);
            capsule = new JetStreamPlaybackSpecsView(activity);
            FrameLayout.LayoutParams capsuleParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(46), Gravity.TOP | Gravity.LEFT);
            capsuleParams.setMargins(dp(32), dp(32), dp(32), 0);
            host.addView(capsule, capsuleParams);
            activity.setContentView(host);
            assertTrue(focusTarget.requestFocus());
        });
        await(() -> focusTarget.hasWindowFocus(), 3000, "activity window");
        // Create the window session first, so this process receives system animator-scale updates.
        // The general preview workflow disables motion; this suite exercises the real transitions.
        setMotionScale(1f);
    }

    @After public void cleanup() {
        try {
            if (activity != null) main(activity::finish);
            instrumentation.waitForIdleSync();
        } finally {
            if (motionScaleSaved) {
                if (originalMotionScale == null) {
                    shell("settings delete global animator_duration_scale");
                    awaitMotionScale(1f);
                } else {
                    setMotionScale(Float.parseFloat(originalMotionScale));
                }
            }
        }
    }

    @Test public void shouldClipMotionAndHoldExpandedForThreeSecondsWithoutRelayoutOrStealingFocus() {
        AtomicReference<ValueAnimator> entrance = new AtomicReference<>();
        main(() -> {
            capsule.show("1080P SDR", "H.264 8bit", "AAC", "2.0", null);
            capsule.post(() -> {
                ValueAnimator current = currentAnimator();
                if (current != null) {
                    current.pause();
                    current.setCurrentPlayTime(180);
                    entrance.set(current);
                }
            });
        });
        await(() -> entrance.get() != null && capsule.getWidth() > 0, 2000, "paused entrance circle");
        int width = value(capsule::getWidth);
        int height = value(capsule::getHeight);
        Rect fullBounds = value(() -> {
            Rect bounds = new Rect();
            capsule.getExpandedBounds(bounds);
            return bounds;
        });
        assertEquals(width, fullBounds.width());
        assertEquals(height, fullBounds.height());
        assertEquals(dp(46), height);
        assertTrue("Expanded natural width", width > height * 3);
        assertTransparentBeyond(height + 2);
        NativeUiEvidence.capture("playback-specs-circle");

        ValueAnimator expansion = finishAndPauseNext(entrance.get(), 520, 90);
        assertTransparentBeyond(width - dp(3));
        assertStableBoundsAndFocus(width, height);
        NativeUiEvidence.capture("playback-specs-mid-expansion");
        AtomicReference<ValueAnimator> textFade = new AtomicReference<>();
        main(() -> {
            expansion.end();
            // Queue immediately after the view's hold callback. Even a busy emulator cannot skip
            // this short phase between separate instrumentation polls.
            capsule.postDelayed(() -> {
                ValueAnimator current = currentAnimator();
                if (current != null && current.getDuration() == 150) {
                    current.pause();
                    current.setCurrentPlayTime(75);
                    textFade.set(current);
                }
            }, 3000);
        });
        long expandedAt = SystemClock.elapsedRealtime();
        assertNull("The three-second readable hold has no animator", value(this::currentAnimator));
        NativeUiEvidence.capture("playback-specs-expanded");
        for (int frame = 0; frame < 10; frame++) {
            SystemClock.sleep(90);
            assertStableBoundsAndFocus(width, height);
        }
        sleepUntil(expandedAt + 2800);
        assertTrue(value(capsule::isActive));
        assertNull("The readable hold must not include entrance time", value(this::currentAnimator));

        await(() -> textFade.get() != null, 1200, "text fade before shrinking");
        assertTrue("Keep the complete content for about 3000ms", SystemClock.elapsedRealtime() - expandedAt >= 2900);
        Bitmap fading = pixels();
        try {
            assertTrue("The background stays expanded while text fades",
                    Color.alpha(fading.getPixel(width - dp(3), height / 2)) > 0);
        } finally {
            fading.recycle();
        }
        ValueAnimator shrink = finishAndPauseNext(textFade.get(), 380, 100);
        assertTransparentBeyond(width - dp(3));
        assertNoBrightTextBeyondLogo();
        assertStableBoundsAndFocus(width, height);
        NativeUiEvidence.capture("playback-specs-shrink");
        main(shrink::resume);
        await(() -> !capsule.isActive(), 1500, "automatic collapse and disappearance");
        assertEquals(View.GONE, value(capsule::getVisibility).intValue());
        assertNull(value(this::currentAnimator));
    }

    @Test public void shouldClipRoundCornersAndConstrainLongSpecsInPreviewWindow() {
        main(() -> {
            ViewGroup.LayoutParams params = capsule.getLayoutParams();
            params.width = dp(242);
            capsule.setLayoutParams(params);
        });
        show("2160P Dolby Vision", "HEVC 12bit", "Dolby TrueHD", "7.1", "TV-MA");
        awaitExpanded();
        Bitmap bitmap = pixels();
        try {
            assertEquals("Transparent rounded top-left", 0, Color.alpha(bitmap.getPixel(0, 0)));
            assertEquals("Transparent rounded top-right", 0, Color.alpha(bitmap.getPixel(bitmap.getWidth() - 1, 0)));
            assertTrue("Pill background reaches its center", Color.alpha(bitmap.getPixel(bitmap.getWidth() / 2,
                    bitmap.getHeight() / 2)) > 0);
            assertEquals(dp(242), bitmap.getWidth());
        } finally {
            bitmap.recycle();
        }
        assertTrue("Ellipsized facts remain available to accessibility",
                value(capsule::getContentDescription).toString().contains("Dolby Vision"));
        NativeUiEvidence.capture("playback-specs-preview-width");
    }

    @Test public void shouldAssembleBothInternalLogoCornersAndSettleToTheAppMark() {
        AtomicReference<ValueAnimator> entrance = new AtomicReference<>();
        main(() -> {
            capsule.show("2160P HDR10", "HEVC 10bit", "E-AC-3", "5.1", "TV-MA");
            capsule.post(() -> {
                ValueAnimator current = currentAnimator();
                if (current != null) {
                    current.pause();
                    current.setCurrentPlayTime(35);
                    entrance.set(current);
                }
            });
        });
        await(() -> entrance.get() != null && capsule.getWidth() > 0, 2000, "paused logo assembly");
        List<Bitmap> assembly = new ArrayList<>();
        Bitmap settled = null;
        try {
            for (long time : new long[]{35, 80, 150}) {
                main(() -> entrance.get().setCurrentPlayTime(time));
                assembly.add(normalizedLogo());
                if (time == 80) NativeUiEvidence.capture("playback-specs-logo-early");
            }
            ValueAnimator expansion = finishAndPauseNext(entrance.get(), 520, 20);
            for (long time : new long[]{20, 70, 150}) {
                main(() -> expansion.setCurrentPlayTime(time));
                assembly.add(normalizedLogo());
                if (time == 70) NativeUiEvidence.capture("playback-specs-logo-mid");
            }
            main(expansion::end);
            settled = normalizedLogo();
            assertNull("Internal assembly stops for the readable hold", value(this::currentAnimator));
            for (boolean upper : new boolean[]{true, false}) {
                float[] target = cornerCenter(settled, upper);
                assertNotNull("The settled mark keeps both terracotta corners", target);
                double movement = 0;
                for (Bitmap frame : assembly) {
                    float[] center = cornerCenter(frame, upper);
                    if (center != null) movement = Math.max(movement,
                            Math.hypot(center[0] - target[0], center[1] - target[1]));
                }
                assertTrue("Each terracotta corner must move inside the mark after removing outer motion", movement > 2);
            }
            assertMatchesAppLogo(settled);
            NativeUiEvidence.capture("playback-specs-logo-settled");
        } finally {
            for (Bitmap frame : assembly) frame.recycle();
            if (settled != null) settled.recycle();
        }
    }

    @Test public void shouldDrawTheCertificationWithoutAHardOutlineWhenTheNaturalWidthFits() {
        show("2160P HDR10", "HEVC 10bit", "E-AC-3", "5.1", "TV-MA");
        awaitExpanded();
        Bitmap bitmap = pixels();
        try {
            // Look at rendered glyphs inside the rightmost badge, clear of the rounded outer rim.
            assertTrue("A fitting classification must be drawn, not only announced",
                    containsBrightPixel(bitmap, bitmap.getWidth() - dp(44), dp(15),
                            bitmap.getWidth() - dp(20), dp(32)));
            // The strip is above the glyphs and clear of the capsule rim. An outlined badge has
            // a bright ridge here; a softly filled rating stays at or below its interior tone.
            int center = bitmap.getHeight() / 2;
            for (int x = bitmap.getWidth() - dp(42); x < bitmap.getWidth() - dp(29); x++) {
                int inside = brightness(bitmap.getPixel(x, center - dp(7)));
                for (int y = center - dp(11); y < center - dp(7); y++) {
                    assertTrue("The rating must not regain a hard rectangular outline",
                            brightness(bitmap.getPixel(x, y)) <= inside + 16);
                }
            }
        } finally {
            bitmap.recycle();
        }
        NativeUiEvidence.capture("playback-specs-hdr-rating");
    }

    @Test public void shouldReplacePlaybackAndCancelThePreviousHoldWhenDetached() {
        show("1080P SDR", "H.264 8bit", "AAC", "2.0", null);
        SystemClock.sleep(2000);
        show("2160P HDR10", "HEVC 10bit", "E-AC-3", "5.1", "16+");
        // This is beyond the original presentation's complete 4450ms lifecycle.
        SystemClock.sleep(2700);
        assertTrue("Previous playback must not hide its replacement", value(capsule::isActive));
        assertTrue(value(capsule::getContentDescription).toString().contains("2160P HDR10"));
        main(() -> {
            host.removeView(capsule);
            assertFalse(capsule.isActive());
            assertEquals(View.GONE, capsule.getVisibility());
            assertNull(currentAnimator());
            host.addView(capsule);
        });
        SystemClock.sleep(1800);
        assertFalse("Reattachment does not restart a dismissed presentation", value(capsule::isActive));
        assertTrue(value(focusTarget::hasFocus));
    }

    @Test public void shouldUpdateLateTracksWithoutExtendingHoldAndNeverRecycleBorrowedBackdrop() {
        long started = SystemClock.elapsedRealtime();
        show("1080P", "H.264", null, null, null);
        Bitmap backdrop = Bitmap.createBitmap(64, 12, Bitmap.Config.ARGB_8888);
        backdrop.eraseColor(Color.rgb(180, 100, 65));
        try {
            main(() -> capsule.setBackdrop(backdrop));
            SystemClock.sleep(1900);
            main(() -> capsule.update("1080P SDR", "H.264 8bit", "AAC", "2.0", null));
            assertTrue(value(capsule::getContentDescription).toString().contains("AAC"));
            NativeUiEvidence.capture("playback-specs-glass");
            sleepUntil(started + 4900);
            assertFalse("Metadata updates cannot extend the presentation", value(capsule::isActive));
            assertFalse("Bitmap ownership stays with its producer", backdrop.isRecycled());
        } finally {
            main(() -> capsule.setBackdrop(null));
            backdrop.recycle();
        }
    }

    @Test public void shouldCancelWhenThePlaybackContainerBecomesHidden() {
        show("1080P SDR", "H.264 8bit", "AAC", "2.0", null);
        main(() -> {
            host.setVisibility(View.INVISIBLE);
            assertFalse("Hidden video containers cancel the presentation immediately", capsule.isActive());
            assertNull(currentAnimator());
            host.setVisibility(View.VISIBLE);
        });
        SystemClock.sleep(1000);
        assertFalse("Showing the container cannot revive old playback", value(capsule::isActive));
    }

    @Test public void shouldHonorDisabledAnimationsAndKeepTheFullReadableHold() {
        setMotionScale(0f);
        long started = SystemClock.elapsedRealtime();
        show("1080P SDR", "H.264 8bit", "AAC", "2.0", null);
        awaitExpanded();
        assertEquals(1f, value(capsule::getAlpha), 0f);
        assertNull("Reduced motion must not start an animator", value(this::currentAnimator));
        NativeUiEvidence.capture("playback-specs-reduced-motion");
        sleepUntil(started + 2700);
        assertTrue("Reduced motion still leaves time to read the specifications", value(capsule::isActive));
        await(() -> !capsule.isActive(), 1100, "reduced-motion timeout");
        assertEquals(View.GONE, value(capsule::getVisibility).intValue());
        assertTrue(value(focusTarget::hasFocus));
    }

    @Test public void shouldCancelAnEntranceImmediatelyAndKeepEmptySpecsHidden() {
        main(() -> {
            capsule.show("1080P", "H.264", null, null, null);
            capsule.dismiss();
        });
        SystemClock.sleep(1000);
        assertFalse(value(capsule::isActive));
        assertEquals(View.GONE, value(capsule::getVisibility).intValue());
        main(() -> capsule.show(null, "", " ", null, "TV-MA"));
        assertFalse("A classification alone is not a playback specification", value(capsule::isActive));
        assertTrue(value(focusTarget::hasFocus));
    }

    /** A continuous showcase for CI screenrecord, with unpaused platform animations and real frames. */
    @Test public void shouldRecordFrameMetricsForTheNaturalPresentation() throws Exception {
        HandlerThread metricsThread = new HandlerThread("playback-specs-frame-metrics");
        metricsThread.start();
        List<Long> frameDurations = new ArrayList<>();
        int[] callbacksDropped = {0};
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) -> {
            long duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            if (duration >= 0) frameDurations.add(duration);
            callbacksDropped[0] += dropped;
        };
        long started = SystemClock.elapsedRealtime();
        try {
            main(() -> activity.getWindow().addOnFrameMetricsAvailableListener(listener,
                    new Handler(metricsThread.getLooper())));
            show("2160P HDR10", "HEVC 10bit", "E-AC-3", "5.1", "TV-MA");
            awaitExpanded();
            long expandedAt = SystemClock.elapsedRealtime();
            sleepUntil(expandedAt + 2800);
            main(() -> {
                assertTrue("The natural animation leaves three seconds to read", capsule.isActive());
                assertNull("The complete pill stays still during its hold", currentAnimator());
                assertEquals(1f, capsule.getAlpha(), 0f);
                assertTrue(focusTarget.hasFocus());
            });
            await(() -> !capsule.isActive(), 2000, "natural text fade, collapse, and logo disappearance");
            assertTrue(SystemClock.elapsedRealtime() - expandedAt >= 3000);
            assertEquals(View.GONE, value(capsule::getVisibility).intValue());
        } finally {
            try {
                main(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
            } finally {
                metricsThread.quitSafely();
                metricsThread.join(2000);
                assertFalse("Stop collecting window metrics after the showcase", metricsThread.isAlive());
                writeFrameMetrics(frameDurations, callbacksDropped[0], SystemClock.elapsedRealtime() - started);
            }
        }
        assertFalse("Collect actual rendered window frames", frameDurations.isEmpty());
    }

    private void writeFrameMetrics(List<Long> durations, int callbacksDropped, long presentationMillis) throws Exception {
        List<Long> sorted = new ArrayList<>(durations);
        Collections.sort(sorted);
        int overBudget = 0;
        for (long duration : durations) if (duration > 16_700_000L) overBudget++;
        JSONObject summary = new JSONObject();
        summary.put("source", "Window.FrameMetrics.TOTAL_DURATION");
        summary.put("totalFrames", durations.size());
        summary.put("p50Ms", percentileMillis(sorted, 0.50));
        summary.put("p95Ms", percentileMillis(sorted, 0.95));
        summary.put("framesOver16_7Ms", overBudget);
        summary.put("metricCallbacksDropped", callbacksDropped);
        summary.put("presentationMillis", presentationMillis);
        summary.put("animatorDurationScale", 1);
        summary.put("hardwareThresholdsEnforced", false);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, "playback-specs-frame-metrics.json"))) {
            output.write((summary.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private ValueAnimator finishAndPauseNext(ValueAnimator previous, long duration, long playTime) {
        return value(() -> {
            previous.end();
            ValueAnimator next = currentAnimator();
            assertNotNull("The next animation phase must start", next);
            assertEquals(duration, next.getDuration());
            next.pause();
            next.setCurrentPlayTime(playTime);
            return next;
        });
    }

    private void assertStableBoundsAndFocus(int width, int height) {
        main(() -> {
            assertEquals("No animation relayout", width, capsule.getWidth());
            assertEquals(height, capsule.getHeight());
            assertFalse(capsule.isLayoutRequested());
            assertTrue(focusTarget.hasFocus());
            assertFalse(capsule.isFocusable());
            assertFalse(capsule.isClickable());
        });
    }

    private void assertTransparentBeyond(int firstColumn) {
        Bitmap bitmap = pixels();
        try {
            for (int x = firstColumn; x < bitmap.getWidth(); x++) {
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    assertEquals("Hidden content cannot overflow the animated pill", 0, Color.alpha(bitmap.getPixel(x, y)));
                }
            }
        } finally {
            bitmap.recycle();
        }
    }

    private void assertNoBrightTextBeyondLogo() {
        Bitmap bitmap = pixels();
        try {
            assertFalse("Text and dividers disappear before the background starts shrinking",
                    containsBrightPixel(bitmap, dp(50), dp(8), bitmap.getWidth(), bitmap.getHeight() - dp(8)));
        } finally {
            bitmap.recycle();
        }
    }

    private boolean containsBrightPixel(Bitmap bitmap, int left, int top, int right, int bottom) {
        for (int x = Math.max(0, left); x < Math.min(right, bitmap.getWidth()); x++) {
            for (int y = Math.max(0, top); y < Math.min(bottom, bitmap.getHeight()); y++) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) > 200 && Color.red(color) > 150
                        && Color.green(color) > 150 && Color.blue(color) > 150) return true;
            }
        }
        return false;
    }

    private int brightness(int color) {
        return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
    }

    private Bitmap normalizedLogo() {
        return value(() -> {
            Bitmap bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            float center = capsule.getHeight() / 2f;
            float density = instrumentation.getTargetContext().getResources().getDisplayMetrics().density;
            float size = (int) Math.min(capsule.getHeight() * 0.68f, 32f * density);
            float scale = 160f / size / floatField("logoScale");
            // View.draw excludes the host View's scale. Undo the logo Canvas transform as well,
            // so a static drawable cannot pass merely by rotating or scaling the complete disc.
            canvas.translate(80f, 80f);
            canvas.scale(scale, scale);
            canvas.rotate(-floatField("logoRotation"));
            canvas.translate(-center, -center);
            capsule.draw(canvas);
            return bitmap;
        });
    }

    private float[] cornerCenter(Bitmap bitmap, boolean upper) {
        int count = 0;
        long sumX = 0;
        long sumY = 0;
        for (int x = upper ? 80 : 0; x < (upper ? 160 : 80); x++) {
            for (int y = upper ? 0 : 80; y < (upper ? 80 : 160); y++) {
                int color = bitmap.getPixel(x, y);
                if (Color.red(color) - Color.green(color) > 45 && Color.red(color) - Color.blue(color) > 55) {
                    count++;
                    sumX += x;
                    sumY += y;
                }
            }
        }
        return count < 32 ? null : new float[]{sumX / (float) count, sumY / (float) count};
    }

    private void assertMatchesAppLogo(Bitmap actual) {
        Bitmap expected = value(() -> {
            Bitmap bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Path circle = new Path();
            circle.addCircle(80f, 80f, 80f, Path.Direction.CW);
            canvas.clipPath(circle);
            Drawable drawable = activity.getDrawable(R.drawable.ic_logo);
            assertNotNull(drawable);
            drawable.setBounds(0, 0, 160, 160);
            drawable.draw(canvas);
            return bitmap;
        });
        try {
            int checked = 0;
            int different = 0;
            for (int x = 2; x < 158; x++) {
                for (int y = 2; y < 158; y++) {
                    if (Math.hypot(x - 80, y - 80) > 76) continue;
                    int color = expected.getPixel(x, y);
                    // Ignore antialiasing at path edges; compare the original solid geometry.
                    if (color != expected.getPixel(x - 1, y) || color != expected.getPixel(x + 1, y)
                            || color != expected.getPixel(x, y - 1) || color != expected.getPixel(x, y + 1)) continue;
                    checked++;
                    int rendered = actual.getPixel(x, y);
                    if (Math.abs(Color.red(color) - Color.red(rendered)) > 8
                            || Math.abs(Color.green(color) - Color.green(rendered)) > 8
                            || Math.abs(Color.blue(color) - Color.blue(rendered)) > 8) different++;
                }
            }
            assertTrue("Compare the complete disc, monogram, counter, and corners", checked > 15000);
            assertTrue("The settled animation must retain ic_logo geometry and colors: " + different + "/" + checked,
                    different < checked / 100);
        } finally {
            expected.recycle();
        }
    }

    private float floatField(String name) {
        try {
            Field field = JetStreamPlaybackSpecsView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getFloat(capsule);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private Bitmap pixels() {
        return value(() -> {
            Bitmap bitmap = Bitmap.createBitmap(capsule.getWidth(), capsule.getHeight(), Bitmap.Config.ARGB_8888);
            capsule.draw(new Canvas(bitmap));
            return bitmap;
        });
    }

    // Freeze short platform-animation phases for pixel assertions and screenshots.
    private ValueAnimator currentAnimator() {
        try {
            Field field = JetStreamPlaybackSpecsView.class.getDeclaredField("animator");
            field.setAccessible(true);
            return (ValueAnimator) field.get(capsule);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void show(String video, String codec, String audio, String channels, String rating) {
        main(() -> capsule.show(video, codec, audio, channels, rating));
        await(() -> capsule.getWidth() > 0 && !capsule.isLayoutRequested(), 2000, "capsule measurement");
    }

    private void awaitExpanded() {
        await(() -> capsule.isActive() && capsule.getAlpha() == 1f && currentAnimator() == null,
                2000, "fully expanded capsule");
    }

    private void setMotionScale(float scale) {
        shell("settings put global animator_duration_scale " + Float.toString(scale));
        awaitMotionScale(scale);
    }

    private void awaitMotionScale(float scale) {
        await(() -> {
            if (Settings.Global.getFloat(instrumentation.getTargetContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != scale) return false;
            if (Build.VERSION.SDK_INT >= 33) return ValueAnimator.getDurationScale() == scale;
            return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled() == (scale > 0f);
        }, 3000, "system animation scale " + scale);
    }

    private void shell(String command) {
        try {
            UiDevice.getInstance(instrumentation).executeShellCommand(command);
        } catch (IOException error) {
            throw new AssertionError("Cannot configure preview animation scale", error);
        }
    }

    private int dp(int value) {
        return Math.round(value * instrumentation.getTargetContext().getResources().getDisplayMetrics().density);
    }

    private void sleepUntil(long deadline) {
        long delay = deadline - SystemClock.elapsedRealtime();
        if (delay > 0) SystemClock.sleep(delay);
    }

    private void await(BooleanSupplier condition, long timeout, String message) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
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
        if (failure.get() != null) throw new AssertionError("Capsule UI operation failed", failure.get());
    }

    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        main(() -> result.set(action.get()));
        return result.get();
    }
}
