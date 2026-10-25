package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.ThemeMode;
import com.fongmi.android.tv.ui.activity.TimeshiftPreviewActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TimeshiftControlsTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private TimeshiftPreviewActivity activity;

    @Test public void shouldOfferReturnToLiveAndRetainTouchToDpadSeekingInBothThemes() throws Exception {
        ThemeMode original = Setting.getThemeMode();
        try {
            for (boolean light : new boolean[]{false, true}) {
                main(() -> Setting.putLightTheme(light));
                activity = (TimeshiftPreviewActivity) instrumentation.startActivitySync(new Intent(
                        instrumentation.getTargetContext(), TimeshiftPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                UiObject2 live = text("返回直播");
                capture(light ? "timeshift-light-replay" : "timeshift-dark-replay");
                live.click();
                waitFor(() -> activity.returnLiveCount == 1, "Touch return to live");
                device.pressDPadLeft();
                device.pressDPadLeft();
                device.pressDPadCenter();
                waitFor(() -> activity.seekCount == 1, "D-pad seek after touching return to live");
                assertTrue(activity.lastSeek >= 0 && activity.lastSeek < 3_600_000);
                text("返回直播").click();
                waitFor(() -> activity.returnLiveCount == 2, "Return to live after D-pad replay");
                capture(light ? "timeshift-light-live" : "timeshift-dark-live");
                main(() -> activity.setSupported(false));
                SystemClock.sleep(400);
                if (Build.VERSION.SDK_INT >= 33) instrumentation.getUiAutomation().clearCache();
                assertNull("Unsupported channels must not offer provider seeking", device.findObject(By.text("返回直播")));
                capture(light ? "timeshift-light-unsupported" : "timeshift-dark-unsupported");
                main(() -> activity.finish());
                activity = null;
            }
        } finally {
            main(() -> {
                if (activity != null) activity.finish();
                Setting.putThemeMode(original);
            });
        }
    }

    private UiObject2 text(String value) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.getUiAutomation().clearCache();
            UiObject2 result = device.findObject(By.text(value));
            if (result != null) return result;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing timeshift control: " + value);
    }

    private void waitFor(java.util.function.BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertTrue(message, condition.getAsBoolean());
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(350);
        File directory = new File(activity.getExternalFilesDir(null), "upstream-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }
        device.dumpWindowHierarchy(new File(directory, name + ".xml"));
    }

    private void main(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { task.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Timeshift UI fixture failed", failure.get());
        instrumentation.waitForIdleSync();
    }
}
