package com.github.catvod.utils;

import com.github.catvod.Init;
import org.junit.After;
import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import static org.junit.Assert.*;

public class DialogTest {
    @After public void clearHost() { Init.setDialog(null); }

    @Test public void closingOwnerCancelsPendingHostRequest() {
        CompletableFuture<String> host = new CompletableFuture<>();
        Init.setDialog((options, callback) -> host);
        Dialog owner = new Dialog();
        assertSame(host, owner.open("{}", null));
        owner.close();
        assertTrue(host.isCancelled());
    }

    @Test public void ownersAreIndependentAndClosedOwnerCannotReopen() throws Exception {
        Init.setDialog((options, callback) -> new CompletableFuture<>());
        Dialog first = new Dialog();
        Dialog second = new Dialog();
        CompletableFuture<String> a = first.open("{}", null);
        CompletableFuture<String> b = second.open("{}", null);
        first.close();
        assertTrue(a.isCancelled());
        assertFalse(b.isDone());
        assertThrows(ExecutionException.class, () -> first.open("{}", null).get());
        second.close();
        assertTrue(b.isCancelled());
    }

    @Test public void closingDuringHostCreationCancelsReturnedRequest() {
        Dialog owner = new Dialog();
        CompletableFuture<String> host = new CompletableFuture<>();
        Init.setDialog((options, callback) -> { owner.close(); return host; });
        assertTrue(owner.open("{}", null).isCancelled());
    }

    @Test public void completedDialogAllowsSequentialRequest() throws Exception {
        Init.setDialog((options, callback) -> CompletableFuture.completedFuture("{\"button\":\"ok\",\"values\":{}}"));
        try (Dialog owner = new Dialog()) {
            assertNotNull(owner.open("{}", null).get());
            assertNotNull(owner.open("{}", null).get());
        }
    }

    @Test public void noHostAndOversizedRequestFailWithoutOpening() {
        try (Dialog owner = new Dialog()) {
            assertThrows(ExecutionException.class, () -> owner.open("{}", null).get());
            Init.setDialog((options, callback) -> { fail("Invalid options reached host"); return null; });
            assertThrows(ExecutionException.class, () -> owner.open(null, null).get());
            assertThrows(ExecutionException.class, () -> owner.open("x".repeat(2 * 1024 * 1024 + 1), null).get());
        }
    }

    @Test public void outerCancellationClosesDialogWithoutDestroyingOwner() {
        Init.setDialog((options, callback) -> new CompletableFuture<>());
        CallTimeout request = new CallTimeout(0);
        try (Dialog owner = new Dialog()) {
            Runnable restore = request.enter();
            CompletableFuture<String> host;
            try { host = owner.open("{}", null); } finally { restore.run(); }
            request.close();
            assertTrue(host.isCancelled());
            assertFalse(owner.open("{}", null).isDone());
        }
    }
    @Test public void backgroundPolicyRejectsHostBeforeShowingAndDoesNotPoisonManualOwner() throws Exception {
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        Init.setDialog((options, callback) -> { opened.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        try (Dialog owner = new Dialog()) {
            CallTimeout.withoutDialogs(() -> {
                assertThrows(ExecutionException.class, () -> owner.open("{}", null).get());
                assertTrue("Loader must be able to retry a denied cold initialization", CallTimeout.currentDeniedDialog());
                return null;
            });
            assertEquals(0, opened.get());
            assertFalse(CallTimeout.currentDeniedDialog());
            assertNull(owner.open("{}", null).get());
            assertEquals(1, opened.get());
        }
    }

    @Test public void dialogMarksItsRequestAndRedactsProviderFailureContent() {
        Init.setDialog((options, callback) -> CompletableFuture.completedFuture(null));
        try (CallTimeout timeout = new CallTimeout(1000); Dialog owner = new Dialog()) {
            Runnable restore = timeout.enter();
            try {
                owner.open("{}", null);
                assertTrue(CallTimeout.currentHasDialog());
                Exception safe = timeout.redactFailure(new IllegalArgumentException("FORM_SECRET_SENTINEL"));
                assertFalse(safe.toString().contains("FORM_SECRET_SENTINEL"));
                assertNull(safe.getCause());
            } finally { restore.run(); }
        }
        assertFalse(CallTimeout.currentHasDialog());
    }

}
