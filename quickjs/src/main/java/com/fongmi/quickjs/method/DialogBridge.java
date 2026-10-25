package com.fongmi.quickjs.method;

import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Dialog;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Only the JS executor touches QuickJS values; each dialog belongs to one source invocation. */
public final class DialogBridge implements AutoCloseable {
    private final ExecutorService executor;
    private final Dialog owner;
    private final Map<Integer, Request> requests = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<String>> actions = new ConcurrentHashMap<>();
    private final AtomicInteger actionId = new AtomicInteger();
    private JSFunction deliver;
    private volatile boolean closed;

    public DialogBridge(QuickJSContext context, ExecutorService executor, Dialog owner) {
        this.executor = executor;
        this.owner = owner;
        JSObject nativeObject = context.createNewJSObject();
        nativeObject.setProperty("call", args -> {
            try {
                if (closed) throw new IllegalStateException("Dialog bridge closed");
                JSONObject params = new JSONObject((String) args[1]);
                if ("open".equals(args[0])) open(params);
                else if ("actionResult".equals(args[0])) completeAction(params);
                else throw new IllegalArgumentException("Unknown dialog operation");
                return "{}";
            } catch (Exception error) {
                return failure(error);
            }
        });
        context.getGlobalObject().setProperty("__SPIDER_DIALOG_NATIVE__", nativeObject);
        nativeObject.release();
        context.evaluate(Asset.read("js/lib/ui.js"));
        deliver = context.getGlobalObject().getJSFunction("__SPIDER_DIALOG_DELIVER__");
    }

    private void open(JSONObject params) throws Exception {
        Invocation invocation = Invocation.current();
        if (invocation == null) throw new IllegalStateException("Dialog requires an active source request");
        invocation.checkActive();
        int id = params.getInt("id");
        Request request = new Request(id, invocation);
        if (requests.putIfAbsent(id, request) != null) throw new IllegalArgumentException("Duplicate dialog request");
        request.source = owner.open(params.getJSONObject("options").toString(), params.optBoolean("callback") ? request::onAction : null);
        invocation.await(request.source);
        request.source.whenComplete((value, error) -> {
            requests.remove(id, request);
            for (CompletableFuture<String> action : request.pending) action.cancel(true);
            emit("result", id, error == null ? success(value) : failure(error), invocation);
        });
        if (closed) request.source.cancel(true);
    }

    private void completeAction(JSONObject params) throws Exception {
        CompletableFuture<String> action = actions.remove(params.getInt("id"));
        if (action == null) return;
        if (params.has("error")) action.completeExceptionally(new IllegalStateException("Dialog callback failed"));
        else action.complete(params.isNull("value") ? null : params.getJSONObject("value").toString());
    }

    private final class Request {
        final int id;
        final Invocation invocation;
        final Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();
        volatile CompletableFuture<String> source;

        Request(int id, Invocation invocation) {
            this.id = id;
            this.invocation = invocation;
        }

        CompletableFuture<String> onAction(String event) {
            CompletableFuture<String> result = new CompletableFuture<>();
            try {
                if (closed || source == null || source.isDone()) throw new IllegalStateException("Dialog is closed");
                Runnable finish = invocation.beginAction(result);
                int callbackId = actionId.incrementAndGet();
                pending.add(result);
                actions.put(callbackId, result);
                result.whenComplete((value, error) -> {
                    pending.remove(result);
                    actions.remove(callbackId, result);
                    finish.run();
                });
                String payload = new JSONObject().put("request", id).put("event", new JSONObject(event)).toString();
                executor.execute(Invocation.wrap(invocation, () -> {
                    if (closed || source.isDone() || result.isDone() || deliver == null) result.cancel(true);
                    else deliver.callVoid("action", callbackId, payload);
                }));
                if (closed || source.isDone()) result.cancel(true);
            } catch (Exception error) {
                result.completeExceptionally(error);
            }
            return result;
        }
    }

    private void emit(String kind, int id, String json, Invocation invocation) {
        if (closed) return;
        try {
            executor.execute(Invocation.wrap(invocation, () -> {
                if (!closed && deliver != null) deliver.callVoid(kind, id, json);
            }));
        } catch (RejectedExecutionException ignored) {
            // The bridge is being released by its owner.
        }
    }

    private static String success(String value) {
        try {
            return new JSONObject().put("value", value == null ? JSONObject.NULL : new JSONObject(value)).toString();
        } catch (Exception error) {
            return failure(error);
        }
    }

    private static String failure(Throwable error) {
        try {
            // Do not copy arbitrary callback exception messages (which may contain form values).
            return new JSONObject().put("error", error instanceof java.util.concurrent.CancellationException
                    ? "Dialog cancelled" : "Dialog request failed").toString();
        } catch (Exception impossible) {
            return "{\"error\":\"Dialog request failed\"}";
        }
    }

    /** Called on the JS executor before destroying its context. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Request request : requests.values()) if (request.source != null) request.source.cancel(true);
        for (CompletableFuture<String> action : actions.values()) action.cancel(true);
        requests.clear();
        actions.clear();
        if (deliver != null) {
            try { deliver.callVoid("close", 0, ""); }
            finally { deliver.release(); deliver = null; }
        }
    }
}
