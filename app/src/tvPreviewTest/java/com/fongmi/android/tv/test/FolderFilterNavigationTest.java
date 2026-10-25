package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.ui.activity.TvFolderPreviewActivity;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.ui.fragment.TypeFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Exercises actual folder back stacks, filter views and SiteApi category requests. */
@RunWith(AndroidJUnit4.class)
public final class FolderFilterNavigationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private TvFolderPreviewActivity activity;
    private TvFolderPreviewActivity.Session fixture;

    @Before
    public void open() {
        main(() -> fixture = TvFolderPreviewActivity.openFixture(instrumentation.getTargetContext()));
        activity = (TvFolderPreviewActivity) instrumentation.startActivitySync(intent());
        await("root category loads", () -> TvFolderPreviewActivity.ROOT_ID.equals(activity.currentCategoryId())
                && fixture.requested(TvFolderPreviewActivity.ROOT_ID, null) && categoryEntryVisible(TvFolderPreviewActivity.ROOT_ID));
    }

    @After
    public void close() {
        if (activity != null) main(activity::finish);
        if (activity != null) await("fixture activity closes", activity::isDestroyed);
        if (fixture != null) main(fixture::close);
    }

    @Test
    public void filterRequestedFromCommitReturnsToRootBeforeFetching() throws Exception {
        openNestedFolders();
        main(() -> {
            FragmentManager manager = activity.folder().getChildFragmentManager();
            manager.beginTransaction().runOnCommit(() -> {
                activity.showFilters(true);
                assertEquals("Navigation waits until the current transaction finishes", 2, manager.getBackStackEntryCount());
            }).commit();
            manager.executePendingTransactions();
        });
        awaitRootFilters();
        selectRootFilter();
        assertFalse("A root filter must never be sent to the nested category", fixture.requested("folder-level-2", "A"));
        capture("root-filter-from-commit");
    }

    @Test
    public void savedStateDefersFilterAndRefreshUntilResume() throws Exception {
        openNestedFolders();
        device.pressHome();
        await("activity saves its child navigation state", () -> activity.folder().getChildFragmentManager().isStateSaved()
                && !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
        main(() -> {
            activity.showFilters(true);
            activity.folder().onRefresh();
            assertEquals("Saved navigation cannot be popped", 2, activity.folder().getChildFragmentManager().getBackStackEntryCount());
            assertEquals("folder-level-2", activity.currentCategoryId());
        });
        instrumentation.getTargetContext().startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await("activity resumes", () -> activity.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED);
        awaitRootFilters();
        selectRootFilter();
        capture("root-filter-after-resume");
    }

    @Test
    public void viewRecreationRetainsNestedNavigationAndQueuedRootFilter() throws Exception {
        openNestedFolders();
        FolderFragment folder = activity.folder();
        main(() -> activity.getSupportFragmentManager().beginTransaction().detach(folder).commitNow());
        main(() -> assertNull(folder.getView()));
        main(() -> activity.getSupportFragmentManager().beginTransaction().attach(folder).commitNow());
        await("nested category survives parent view recreation", () -> "folder-level-2".equals(activity.currentCategoryId())
                && folder.getChildFragmentManager().getBackStackEntryCount() == 2);
        main(() -> {
            assertEquals("folder-level-2", activity.currentCategoryId());
            assertEquals(2, folder.getChildFragmentManager().getBackStackEntryCount());
            activity.getSupportFragmentManager().beginTransaction().detach(folder).commitNow();
            folder.getType().setFilter(true);
            folder.toggleFilter(true);
            folder.onRefresh();
            assertNull(folder.getView());
            activity.getSupportFragmentManager().beginTransaction().attach(folder).commitNow();
        });
        awaitRootFilters();
        selectRootFilter();
        capture("root-filter-after-view-recreation");
    }

    @Test
    public void cancelledQueuedFilterKeepsTheCurrentFolder() throws Exception {
        openNestedFolders();
        main(() -> {
            FragmentManager manager = activity.folder().getChildFragmentManager();
            manager.beginTransaction().runOnCommit(() -> {
                activity.showFilters(true);
                activity.showFilters(false);
            }).commit();
            manager.executePendingTransactions();
        });
        main(() -> {
            assertEquals(2, activity.folder().getChildFragmentManager().getBackStackEntryCount());
            assertEquals("folder-level-2", activity.currentCategoryId());
            assertTrue("The current folder retains its rendered results", categoryEntryVisible("folder-level-2"));
            assertNull(filterOption());
        });
        capture("cancelled-filter-keeps-folder");
    }

    private void openNestedFolders() {
        main(() -> activity.folder().openFolder("folder-level-1", new HashMap<>()));
        await("first folder loads", () -> "folder-level-1".equals(activity.currentCategoryId())
                && fixture.requested("folder-level-1", null) && categoryEntryVisible("folder-level-1"));
        main(() -> activity.folder().openFolder("folder-level-2", new HashMap<>()));
        await("second folder loads", () -> "folder-level-2".equals(activity.currentCategoryId())
                && fixture.requested("folder-level-2", null) && categoryEntryVisible("folder-level-2"));
    }

    private void awaitRootFilters() {
        await("root filter controls become visible", () -> TvFolderPreviewActivity.ROOT_ID.equals(activity.currentCategoryId())
                && activity.folder().getChildFragmentManager().getBackStackEntryCount() == 0 && filterOption() != null);
    }

    private void selectRootFilter() {
        main(() -> {
            TextView option = filterOption();
            assertNotNull(option);
            assertTrue("The actual filter control accepts a click", option.performClick());
        });
        await("filter applies to the root request", () -> fixture.requested(TvFolderPreviewActivity.ROOT_ID, "A"));
    }

    private TextView filterOption() {
        TypeFragment category = activity.currentCategory();
        return category == null ? null : findText(category.getView(), TvFolderPreviewActivity.FILTER_OPTION);
    }

    private boolean categoryEntryVisible(String id) {
        TypeFragment category = activity.currentCategory();
        return category != null && findText(category.getView(), "Entry in " + id) != null;
    }

    private TextView findText(View view, String text) {
        if (view == null || !view.isShown()) return null;
        if (view instanceof TextView label && view.getWidth() > 0 && view.getHeight() > 0
                && text.contentEquals(label.getText())) return label;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Intent intent() {
        return new Intent(instrumentation.getTargetContext(), TvFolderPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    private void await(String description, BooleanSupplier ready) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean complete = new AtomicBoolean();
        do {
            main(() -> complete.set(ready.getAsBoolean()));
            if (complete.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(description);
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        File directory = new File(activity.getExternalFilesDir(null), "folder-filter-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
        device.dumpWindowHierarchy(new File(directory, name + ".xml"));
    }
}
