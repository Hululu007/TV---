package com.fongmi.android.tv.player;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

public class TimeshiftRequestTest {

    @Test public void shouldReadActualHttpResolverResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String body = "{\"url\":\"https://example.com/replay.m3u8\",\"parse\":0}";
            server.enqueue(new MockResponse().setBody(body));
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), Runnable::run);
            assertEquals(body, request.resolve(server.url("/resolve?t=1792944000000").toString()).get(3, TimeUnit.SECONDS));
            assertEquals("/resolve?t=1792944000000", server.takeRequest().getPath());
        }
    }

    @Test public void shouldRejectFailedHttpResponses() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("offline"));
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), Runnable::run);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> request.resolve(server.url("/resolve").toString()).get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
        }
    }

    @Test public void shouldRejectOversizedDeclaredResponses() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("x").setHeader("Content-Length", 1024 * 1024));
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), Runnable::run);
            assertThrows(ExecutionException.class,
                    () -> request.resolve(server.url("/resolve").toString()).get(3, TimeUnit.SECONDS));
        }
    }

    @Test public void shouldBoundChunkedBodiesWithoutAContentLength() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setChunkedBody("x".repeat(256 * 1024 + 1), 8192));
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), Runnable::run);
            assertThrows(ExecutionException.class,
                    () -> request.resolve(server.url("/resolve").toString()).get(3, TimeUnit.SECONDS));
        }
    }

    @Test public void shouldDiscardACompletedResponseQueuedBeforeAnotherSeek() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("old"));
            server.enqueue(new MockResponse().setBody("new"));
            BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), main::add);
            ListenableFuture<String> first = request.resolve(server.url("/first").toString());
            Runnable staleDelivery = main.poll(3, TimeUnit.SECONDS);
            assertNotNull(staleDelivery);
            ListenableFuture<String> second = request.resolve(server.url("/second").toString());
            assertTrue(first.isCancelled());
            staleDelivery.run();
            assertFalse(second.isDone());
            Runnable currentDelivery = main.poll(3, TimeUnit.SECONDS);
            assertNotNull(currentDelivery);
            currentDelivery.run();
            assertEquals("new", second.get(1, TimeUnit.SECONDS));
        }
    }

    @Test public void shouldDiscardQueuedFailureAfterAnotherSeek() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setBody("live"));
            BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), main::add);
            ListenableFuture<String> first = request.resolve(server.url("/first").toString());
            Runnable staleFailure = main.poll(3, TimeUnit.SECONDS);
            assertNotNull(staleFailure);
            ListenableFuture<String> second = request.resolve(server.url("/live").toString());
            staleFailure.run();
            assertTrue(first.isCancelled());
            assertFalse(second.isDone());
            Runnable current = main.poll(3, TimeUnit.SECONDS);
            assertNotNull(current);
            current.run();
            assertEquals("live", second.get(1, TimeUnit.SECONDS));
        }
    }

    @Test public void shouldCancelQueuedResponseWhenChannelOrOwnerCloses() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("closed-channel"));
            BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), main::add);
            ListenableFuture<String> result = request.resolve(server.url("/resolve").toString());
            Runnable delivery = main.poll(3, TimeUnit.SECONDS);
            assertNotNull(delivery);
            request.cancel();
            delivery.run();
            assertTrue(result.isCancelled());
        }
    }

    @Test public void shouldAllowCancellationByTheFutureOwner() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("old").setBodyDelay(1, TimeUnit.SECONDS));
            TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), Runnable::run);
            ListenableFuture<String> result = request.resolve(server.url("/resolve").toString());
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            assertTrue(result.cancel(false));
            assertTrue(result.isCancelled());
            request.cancel();
        }
    }

    @Test public void shouldReportAnInvalidResolverUrlAsFailure() {
        TimeshiftRequest request = new TimeshiftRequest(new OkHttpClient(), Runnable::run);
        assertThrows(ExecutionException.class, () -> request.resolve("invalid").get(1, TimeUnit.SECONDS));
    }
}
