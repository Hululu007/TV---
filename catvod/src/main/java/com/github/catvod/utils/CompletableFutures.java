package com.github.catvod.utils;

import java.util.concurrent.CompletableFuture;

/** CompletableFuture operations available on the application's Android 7 / API 24 baseline. */
public final class CompletableFutures {

    private CompletableFutures() {
    }

    /** CompletableFuture.failedFuture is a platform API 31 method, without a desugar backport. */
    public static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }
}
