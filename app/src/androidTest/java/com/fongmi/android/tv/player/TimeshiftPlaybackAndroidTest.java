package com.fongmi.android.tv.player;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Timeshift;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.service.PlaybackSessionPlayer;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

/** Real loopback HTTP and Media3 state/command handling, independent of native decoder ABI. */
@RunWith(AndroidJUnit4.class)
public class TimeshiftPlaybackAndroidTest {

    private static final long NOW = 1_792_944_000_000L;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private ResolverServer server;
    private FixtureHost host;
    private AdjustableClock clock;

    @Before public void setup() throws Exception {
        server = new ResolverServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        main(() -> {
            clock = new AdjustableClock();
            host = new FixtureHost();
            host.timeshift = new TimeshiftPlayback(host, new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(), clock);
            host.session = new PlaybackSessionPlayer(host.player, host);
            host.timeshift.start(spec(NOW - 120_000, 60_000));
            host.timeshift.onReady();
        });
    }

    @After public void cleanup() {
        if (server != null) server.release.countDown();
        main(() -> {
            if (host != null) host.timeshift.clear();
            if (host != null) host.session.release();
        });
        if (server != null) server.stop();
    }

    @Test public void shouldExposeAMovingSeekableTimelineAndResolveReturnToLive() throws Exception {
        main(() -> {
            Timeline.Window window = host.session.getCurrentTimeline().getWindow(0, new Timeline.Window());
            assertTrue(window.isSeekable);
            assertTrue(window.isDynamic);
            assertEquals(60_000, window.getDurationMs());
            assertEquals(60_000, window.getDefaultPositionMs());
            assertTrue(host.session.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM));
        });
        seek(20_000).get(5, TimeUnit.SECONDS);
        main(() -> {
            assertTrue(host.timeshift.isReplaying());
            assertEquals(20_000, host.timeshift.getPosition());
            assertEquals("yes", host.headers.get("X-Replay"));
        });
        assertEquals(Long.valueOf(NOW - 40_000), server.times.get(0));
        seek(C.TIME_UNSET).get(5, TimeUnit.SECONDS);
        main(() -> {
            assertFalse(host.timeshift.isReplaying());
            assertEquals(60_000, host.timeshift.getPosition());
        });
        assertEquals(Long.valueOf(0), server.times.get(1));
    }

    @Test public void shouldKeepThePausedEpochWhileTheWindowMoves() {
        main(() -> {
            host.player.position = 0;
            clock.now += 10_000;
            host.timeshift.updateWindow();
            assertEquals(60_000, host.timeshift.getDuration());
            assertEquals(50_000, host.timeshift.getPosition());
            // Resuming advances both clocks equally, retaining the ten-second live offset.
            host.player.position = 5_000;
            clock.now += 5_000;
            host.timeshift.updateWindow();
            assertEquals(50_000, host.timeshift.getPosition());
        });
    }

    @Test public void shouldNotAnchorNewLivePlaybackToThePreviousMoviesPosition() {
        main(() -> {
            host.player.position = 3_600_000;
            host.timeshift.start(spec(NOW - 120_000, 60_000));
            assertEquals(60_000, host.timeshift.getPosition());
            host.player.position = 0;
            host.timeshift.onReady();
            assertEquals(60_000, host.timeshift.getPosition());
        });
    }

    @Test public void shouldTreatTheDisplayedEndAsLiveWhileTheWindowGrows() throws Exception {
        main(() -> {
            host.timeshift.start(spec(NOW - 10_000, 60_000));
            host.timeshift.onReady();
            assertEquals(10_000, host.timeshift.getDuration());
            clock.now += 2000;
        });
        seek(10_000).get(5, TimeUnit.SECONDS);
        assertEquals(Long.valueOf(0), server.times.get(0));
        main(() -> assertFalse(host.timeshift.isReplaying()));
    }

    @Test public void shouldKeepTheCurrentStreamOnHttpFailure() throws Exception {
        server.status = NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE;
        seek(20_000).get(5, TimeUnit.SECONDS);
        main(() -> {
            assertEquals("initial", host.source);
            assertEquals(0, host.applications);
            assertEquals(1, host.errors);
            assertFalse(host.timeshift.isReplaying());
        });
    }

    @Test public void shouldKeepTheCurrentStreamOnMalformedOrNonDirectResults() throws Exception {
        for (String body : List.of("{broken", "{}", "{\"url\":\"https://example.com/v.m3u8\",\"parse\":1}",
                "{\"url\":\"https://example.com/poster.jpg\"}", "{\"url\":{},\"header\":42}",
                "{\"url\":\"https://example.com/v.m3u8\",\"msg\":\"unavailable\"}")) {
            server.body = body;
            seek(20_000).get(5, TimeUnit.SECONDS);
        }
        main(() -> {
            assertEquals("initial", host.source);
            assertEquals(0, host.applications);
            assertEquals(6, host.errors);
        });
    }

    @Test public void shouldCancelPendingResolutionWhenTheChannelChanges() throws Exception {
        server.delayFirst = true;
        ListenableFuture<?> pending = seek(20_000);
        assertTrue(server.received.await(3, TimeUnit.SECONDS));
        main(() -> host.timeshift.start(spec(NOW - 180_000, 90_000)));
        server.release.countDown();
        instrumentation.waitForIdleSync();
        SystemClock.sleep(100);
        assertTrue(pending.isCancelled());
        main(() -> {
            assertEquals("initial", host.source);
            assertEquals(0, host.applications);
            assertEquals(0, host.errors);
        });
    }

    @Test public void shouldIgnoreAnObsoleteSeekAfterANewerSeekSucceeds() throws Exception {
        server.delayFirst = true;
        ListenableFuture<?> old = seek(10_000);
        assertTrue(server.received.await(3, TimeUnit.SECONDS));
        seek(30_000).get(5, TimeUnit.SECONDS);
        server.release.countDown();
        instrumentation.waitForIdleSync();
        SystemClock.sleep(100);
        assertTrue(old.isCancelled());
        main(() -> {
            assertEquals(1, host.applications);
            assertEquals(30_000, host.timeshift.getPosition());
            assertEquals(0, host.errors);
        });
    }

    @Test public void shouldCompleteAndRestoreTimelineWhenApplyingAResultThrows() throws Exception {
        main(() -> host.rejectApplication = true);
        seek(20_000).get(5, TimeUnit.SECONDS);
        main(() -> {
            assertEquals("initial", host.source);
            assertFalse(host.timeshift.isReplaying());
            assertEquals(60_000, host.timeshift.getPosition());
            assertEquals(1, host.errors);
        });
    }

    @Test public void shouldCompleteTheSeekEvenIfErrorPresentationHasDetached() throws Exception {
        main(() -> host.rejectErrorPresentation = true);
        server.status = NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE;
        seek(20_000).get(5, TimeUnit.SECONDS);
        main(() -> assertEquals("initial", host.source));
    }

    @Test public void shouldDisableTimeshiftForAnOrdinaryOrInvalidSource() {
        main(() -> {
            host.timeshift.start(PlaySpec.from("ordinary", "https://example.com/movie.mp4", Map.of(), MediaMetadata.EMPTY));
            assertFalse(host.timeshift.isActive());
            Result result = Result.fromJson("{\"url\":\"https://example.com/live.m3u8\",\"timeshift\":{\"url\":\"file:///{time}\",\"start\":1,\"window\":60000}}");
            host.timeshift.start(PlaySpec.from(result, "invalid", MediaMetadata.EMPTY));
            assertFalse(host.timeshift.isActive());
        });
    }

    private PlaySpec spec(long start, long length) {
        Result result = new Result();
        result.setUrl(server.base() + "/initial.m3u8");
        result.setTimeshift(new Timeshift(server.base() + "/resolve?time={time}", start, length));
        return PlaySpec.from(result, "timeshift-fixture", MediaMetadata.EMPTY);
    }

    private ListenableFuture<?> seek(long position) {
        return read(() -> host.timeshift.seekTo(position));
    }

    private void main(Runnable task) {
        read(() -> { task.run(); return null; });
    }

    private <T> T read(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(task.get()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Main-thread timeshift test failed", failure.get());
        return value.get();
    }

    private static final class FixtureHost implements TimeshiftPlayback.Host, PlaybackSessionPlayer.Callback {
        final FixturePlayer player = new FixturePlayer();
        TimeshiftPlayback timeshift;
        PlaybackSessionPlayer session;
        String source = "initial";
        Map<String, String> headers = Map.of();
        int applications;
        int errors;
        boolean rejectApplication;
        boolean rejectErrorPresentation;

        @Override public Player getPlayer() { return player; }
        @Override public TimeshiftPlayback timeshift() { return timeshift; }
        @Override public void setMediaItems(List<MediaItem> items, int index, long position) { throw new AssertionError("Unexpected item replacement"); }
        @Override public void previous() { throw new AssertionError("Unexpected previous item"); }
        @Override public void next() { throw new AssertionError("Unexpected next item"); }
        @Override public void stop() { throw new AssertionError("Unexpected stop"); }

        @Override public void applyTimeshift(String url, String format, Map<String, String> headers) {
            if (rejectApplication) throw new IllegalArgumentException("Rejected fixture media item");
            this.source = url;
            this.headers = headers;
            applications++;
            player.position = 0;
            timeshift.onReady();
        }

        @Override public void onTimeshiftChanged() {
            if (session != null) session.refreshTimeline();
        }

        @Override public void onTimeshiftError() {
            errors++;
            if (rejectErrorPresentation) throw new IllegalStateException("Detached fixture UI");
        }
    }

    private static final class FixturePlayer extends SimpleBasePlayer {
        long position;

        FixturePlayer() { super(Looper.getMainLooper()); }

        @Override protected State getState() {
            return new State.Builder().setAvailableCommands(new Player.Commands.Builder().addAll(
                            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build())
                    .setPlaylist(List.of(new MediaItemData.Builder("fixture-live")
                            .setMediaItem(new MediaItem.Builder().setUri("https://example.com/live.m3u8").build())
                            .setDurationUs(C.TIME_UNSET).setIsDynamic(true).build()))
                    .setCurrentMediaItemIndex(0).setPlaybackState(Player.STATE_READY)
                    .setContentPositionMs(() -> position).build();
        }
    }

    private static final class AdjustableClock implements Clock {
        long now = NOW;
        @Override public long currentTimeMillis() { return now; }
        @Override public long elapsedRealtime() { return Clock.DEFAULT.elapsedRealtime(); }
        @Override public long uptimeMillis() { return Clock.DEFAULT.uptimeMillis(); }
        @Override public long nanoTime() { return Clock.DEFAULT.nanoTime(); }
        @Override public HandlerWrapper createHandler(Looper looper, Handler.Callback callback) { return Clock.DEFAULT.createHandler(looper, callback); }
        @Override public void onThreadBlocked() {}
    }

    private static final class ResolverServer extends NanoHTTPD {
        final List<Long> times = new CopyOnWriteArrayList<>();
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Response.Status status = Response.Status.OK;
        volatile String body;
        volatile boolean delayFirst;

        ResolverServer() { super("127.0.0.1", 0); }
        String base() { return "http://127.0.0.1:" + getListeningPort(); }

        @Override public Response serve(IHTTPSession session) {
            long time = Long.parseLong(session.getParms().getOrDefault("time", "0"));
            times.add(time);
            boolean first = times.size() == 1;
            received.countDown();
            if (first && delayFirst) {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            String result = body != null ? body : "{\"parse\":0,\"format\":\"application/x-mpegURL\",\"url\":\""
                    + base() + "/media.m3u8?at=" + time + "\",\"header\":{\"X-Replay\":\"yes\"}}";
            return newFixedLengthResponse(status, "application/json", result);
        }
    }
}
