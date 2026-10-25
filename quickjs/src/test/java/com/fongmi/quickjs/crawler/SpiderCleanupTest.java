package com.fongmi.quickjs.crawler;

import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SpiderCleanupTest {
    @Test(timeout = 5000) public void interruptedCallerWaitsForJsThreadCleanupAndKeepsCancellationFlag() throws Exception {
        Spider spider = new Spider("unused-test-source", null);
        Field field = Spider.class.getDeclaredField("executor");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(spider);
        Method release = Spider.class.getDeclaredMethod("releaseJS");
        release.setAccessible(true);
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        executor.execute(() -> {
            entered.countDown();
            try { unblock.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { release.invoke(spider); }
            catch (InvocationTargetException error) { failure.set(error.getCause()); }
            catch (Exception error) { failure.set(error); }
            finally {
                interrupted.set(Thread.currentThread().isInterrupted());
                executor.shutdownNow();
                finished.countDown();
            }
        }, "cancelled-source-init");
        try {
            caller.start();
            assertFalse("Cleanup must wait behind current JS work", finished.await(100, TimeUnit.MILLISECONDS));
            unblock.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertNull("The interrupt must not discard JS cleanup", failure.get());
            assertTrue("Caller cancellation flag must be preserved", interrupted.get());
        } finally {
            unblock.countDown();
            executor.shutdownNow();
            caller.join(2000);
        }
    }
}
