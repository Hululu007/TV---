package com.github.catvod.utils;

import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class CallTimeoutTest {
    @Test public void dialogWaitPausesAndThenResumesDeadline() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        try (CallTimeout timeout = new CallTimeout(150)) {
            Runnable resume = timeout.suspend();
            timeout.start(expired::countDown);
            assertFalse(expired.await(250, TimeUnit.MILLISECONDS));
            resume.run();
            assertTrue(expired.await(2, TimeUnit.SECONDS));
        }
    }

    @Test public void resumeHandleIsIdempotentAcrossOverlappingWaits() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        try (CallTimeout timeout = new CallTimeout(100)) {
            Runnable first = timeout.suspend();
            Runnable second = timeout.suspend();
            timeout.start(expired::countDown);
            first.run();
            first.run();
            assertFalse(expired.await(180, TimeUnit.MILLISECONDS));
            second.run();
            assertTrue(expired.await(2, TimeUnit.SECONDS));
        }
    }

    @Test public void nestedDialogWaitAlsoPausesParentDeadline() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        try (CallTimeout parent = new CallTimeout(150)) {
            Runnable restore = parent.enter();
            try (CallTimeout child = new CallTimeout(1000)) {
                Runnable resume = child.suspend();
                parent.start(expired::countDown);
                assertFalse(expired.await(250, TimeUnit.MILLISECONDS));
                resume.run();
                assertTrue(expired.await(2, TimeUnit.SECONDS));
            } finally { restore.run(); }
        }
        assertNull(CallTimeout.current());
    }

    @Test public void closingScopeCancelsOnlyItsTrackedDialogs() {
        CompletableFuture<String> one = new CompletableFuture<>();
        CompletableFuture<String> two = new CompletableFuture<>();
        CallTimeout first = new CallTimeout(0);
        CallTimeout second = new CallTimeout(0);
        Runnable restore = first.enter();
        try { CallTimeout.trackCurrent(one); } finally { restore.run(); }
        restore = second.enter();
        try { CallTimeout.trackCurrent(two); } finally { restore.run(); }
        first.close();
        assertTrue(one.isCancelled());
        assertFalse(two.isDone());
        second.close();
        assertTrue(two.isCancelled());
    }

    @Test public void closedScopeRejectsLateTrackedDialog() {
        CallTimeout timeout = new CallTimeout(0);
        timeout.close();
        CompletableFuture<String> dialog = new CompletableFuture<>();
        Runnable restore = timeout.enter();
        try { CallTimeout.trackCurrent(dialog); } finally { restore.run(); }
        assertTrue(dialog.isCancelled());
    }

    @Test public void closingSuspendedDeadlineNeverExpiresLater() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        CallTimeout timeout = new CallTimeout(50);
        Runnable resume = timeout.suspend();
        timeout.start(expired::countDown);
        timeout.close();
        resume.run();
        assertFalse(expired.await(150, TimeUnit.MILLISECONDS));
    }
}
