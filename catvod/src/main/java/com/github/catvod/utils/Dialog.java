package com.github.catvod.utils;

import android.os.Looper;

import com.github.catvod.Init;

import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

public final class Dialog implements AutoCloseable {

    private final Set<CompletableFuture<String>> requests = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public JSONObject show(JSONObject options) throws Exception {
        return show(options, null);
    }

    public JSONObject show(JSONObject options, Callback onAction) throws Exception {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Dialog.show requires a worker thread");
        if (options == null) throw new IllegalArgumentException("Dialog options are required");
        if (closed) throw new IllegalStateException("Dialog owner is closed");
        BlockingQueue<Runnable> actions = new LinkedBlockingQueue<>();
        Set<CompletableFuture<String>> callbacks = ConcurrentHashMap.newKeySet();
        CompletableFuture<String> source = open(options.toString(), onAction == null ? null : event -> enqueue(actions, callbacks, onAction, event));
        Thread caller = Thread.currentThread();
        Object state = new Object();
        boolean[] active = {true};
        source.whenComplete((value, error) -> {
            synchronized (state) {
                if (source.isCancelled() && active[0]) caller.interrupt();
            }
            actions.add(() -> {});
        });
        try {
            while (!source.isDone()) {
                Runnable action = waitForAction(actions);
                if (!source.isDone()) action.run();
            }
            String value = source.get();
            return value == null ? null : new JSONObject(value);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            synchronized (state) { active[0] = false; }
            if (!source.isDone()) source.cancel(true);
            for (CompletableFuture<String> callback : callbacks) callback.cancel(true);
        }
    }

    /** Nonblocking bridge used by runtimes which must keep their event loop available. */
    public CompletableFuture<String> open(String options, Function<String, CompletableFuture<String>> onAction) {
        if (closed) return CompletableFutures.failedFuture(new IllegalStateException("Dialog owner is closed"));
        if (options == null || options.length() > 2 * 1024 * 1024) {
            return CompletableFutures.failedFuture(new IllegalArgumentException("Dialog options exceed the supported size"));
        }
        CompletableFuture<String> source;
        try {
            CallTimeout.requireDialogsAllowed();
            CallTimeout.markDialogUse();
            source = Init.dialog(options, onAction);
            if (source == null) throw new IllegalStateException("Dialog host returned no request");
        } catch (RuntimeException error) {
            return CompletableFutures.failedFuture(error);
        }
        requests.add(source);
        source.whenComplete((value, error) -> requests.remove(source));
        CallTimeout.trackCurrent(source);
        if (closed) source.cancel(true);
        return source;
    }

    private Runnable waitForAction(BlockingQueue<Runnable> actions) throws InterruptedException {
        Runnable resume = CallTimeout.suspendCurrent();
        try {
            return actions.take();
        } finally {
            resume.run();
        }
    }

    private CompletableFuture<String> enqueue(BlockingQueue<Runnable> actions, Set<CompletableFuture<String>> callbacks, Callback callback, String event) {
        CompletableFuture<String> result = new CompletableFuture<>();
        callbacks.add(result);
        result.whenComplete((value, error) -> callbacks.remove(result));
        actions.add(() -> invoke(callback, event, result));
        return result;
    }

    private void invoke(Callback callback, String event, CompletableFuture<String> result) {
        if (result.isDone()) return;
        try {
            JSONObject update = callback.onAction(new JSONObject(event));
            result.complete(update == null ? null : update.toString());
        } catch (Exception | Error error) {
            result.completeExceptionally(error);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (CompletableFuture<String> request : requests) request.cancel(true);
    }

    public interface Callback {

        JSONObject onAction(JSONObject event) throws Exception;

    }
}
