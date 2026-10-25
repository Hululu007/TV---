package com.github.catvod.utils;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class CompletableFuturesTest {

    @Test
    public void failedFutureCompletesImmediatelyWithTheOriginalFailure() {
        IOException failure = new IOException("Source failed");
        CompletableFuture<String> future = CompletableFutures.failedFuture(failure);
        assertTrue(future.isDone());
        assertTrue(future.isCompletedExceptionally());
        assertFalse(future.isCancelled());
        assertSame(failure, assertThrows(ExecutionException.class, future::get).getCause());
        assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
        AtomicReference<Throwable> observed = new AtomicReference<>();
        future.whenComplete((value, error) -> observed.set(error));
        assertSame(failure, observed.get());
    }

    @Test
    public void cancellationFailureRetainsCancellationSemantics() {
        CancellationException failure = new CancellationException("Source cancelled");
        CompletableFuture<String> future = CompletableFutures.failedFuture(failure);
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());
        assertTrue(future.isCompletedExceptionally());
        assertSame(failure, assertThrows(CancellationException.class, future::get));
        assertSame(failure, assertThrows(CancellationException.class, future::join));
    }

    @Test
    public void failedFutureCannotBeOverwrittenAndSkipsSuccessCallbacks() {
        IOException failure = new IOException("Original failure");
        CompletableFuture<String> future = CompletableFutures.failedFuture(failure);
        assertFalse(future.cancel(true));
        assertFalse(future.complete("replacement"));
        AtomicBoolean called = new AtomicBoolean();
        CompletableFuture<String> next = future.thenApply(value -> {
            called.set(true);
            return value;
        });
        assertFalse(called.get());
        assertSame(failure, assertThrows(CompletionException.class, next::join).getCause());
    }

    @Test
    public void nullFailureIsRejected() {
        assertThrows(NullPointerException.class, () -> CompletableFutures.failedFuture(null));
    }
}
