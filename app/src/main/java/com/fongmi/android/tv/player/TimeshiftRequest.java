package com.fongmi.android.tv.player;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One owned, cancellable resolver request; responses never directly alter playback. */
public final class TimeshiftRequest {

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final Call.Factory requests;
    private final Executor delivery;
    private Call call;
    private SettableFuture<String> pending;

    public TimeshiftRequest(Call.Factory requests, Executor delivery) {
        this.requests = requests;
        this.delivery = delivery;
    }

    public synchronized ListenableFuture<String> resolve(String url) {
        cancel();
        SettableFuture<String> future = SettableFuture.create();
        pending = future;
        try {
            call = requests.newCall(new Request.Builder().url(url).build());
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException failure) {
                    deliver(future, null, failure);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (response) {
                        deliver(future, read(response), null);
                    } catch (IOException | RuntimeException failure) {
                        deliver(future, null, failure);
                    }
                }
            });
        } catch (RuntimeException failure) {
            deliver(future, null, failure);
        }
        future.addListener(() -> {
            if (future.isCancelled()) cancel(future);
        }, Runnable::run);
        return future;
    }

    public synchronized void cancel() {
        Call oldCall = call;
        SettableFuture<String> oldFuture = pending;
        call = null;
        pending = null;
        if (oldCall != null) oldCall.cancel();
        if (oldFuture != null) oldFuture.cancel(false);
    }

    private synchronized void cancel(SettableFuture<String> future) {
        if (pending == future) cancel();
    }

    private void deliver(SettableFuture<String> future, String body, Throwable failure) {
        delivery.execute(() -> complete(future, body, failure));
    }

    private synchronized void complete(SettableFuture<String> future, String body, Throwable failure) {
        if (pending != future) return;
        pending = null;
        call = null;
        if (failure == null) future.set(body);
        else future.setException(failure);
    }

    private static String read(Response response) throws IOException {
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
            throw new IOException("Invalid timeshift response");
        }
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_RESPONSE_BYTES - output.size()) throw new IOException("Timeshift response is too large");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
