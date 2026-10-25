package com.fongmi.android.tv.service;

import android.app.Instrumentation;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.player.TimeshiftPlayback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Regression coverage for existing business navigation through the production session wrapper. */
@RunWith(AndroidJUnit4.class)
public class PlaybackSessionPlayerAndroidTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void shouldDispatchBusinessNavigationWithOnlyOneVodItem() {
        main(() -> verifyNavigation(false));
    }

    @Test public void shouldDispatchBusinessNavigationWithOnlyOneLiveItem() {
        main(() -> verifyNavigation(true));
    }

    @Test public void shouldKeepStopAndMediaReplacementOwnedByTheService() {
        main(() -> {
            FixturePlayer raw = new FixturePlayer(false);
            Actions actions = new Actions();
            PlaybackSessionPlayer player = new PlaybackSessionPlayer(raw, actions);
            MediaItem item = new MediaItem.Builder().setMediaId("next-source").setUri("https://example.com/new.mp4").build();
            player.setMediaItem(item, 12_000);
            assertEquals(List.of(item), actions.items);
            assertEquals(12_000, actions.startPosition);
            assertEquals(0, raw.replacements);
            player.stop();
            assertEquals(1, actions.stops);
            assertEquals(0, raw.stops);
            player.release();
        });
    }

    @Test public void shouldForwardOrdinaryVodSeekingAndDefaultPosition() {
        main(() -> {
            FixturePlayer raw = new FixturePlayer(false);
            Actions actions = new Actions();
            PlaybackSessionPlayer player = new PlaybackSessionPlayer(raw, actions);
            player.seekTo(45_000);
            assertEquals(45_000, raw.position);
            assertEquals(1, raw.seeks);
            player.seekToDefaultPosition();
            assertEquals(0, raw.position);
            assertEquals(2, raw.seeks);
            assertEquals(0, actions.next + actions.previous);
            player.release();
        });
    }

    private void verifyNavigation(boolean live) {
        FixturePlayer raw = new FixturePlayer(live);
        Actions actions = new Actions();
        PlaybackSessionPlayer player = new PlaybackSessionPlayer(raw, actions);
        assertEquals(1, player.getMediaItemCount());
        assertFalse(player.hasNextMediaItem());
        assertFalse(player.hasPreviousMediaItem());
        player.seekToNext();
        player.seekToNextMediaItem();
        player.seekToPrevious();
        player.seekToPreviousMediaItem();
        assertEquals(2, actions.next);
        assertEquals(2, actions.previous);
        assertEquals("Navigation must not turn into an in-stream seek", 0, raw.seeks);
        player.release();
    }

    private void main(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { task.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Session player regression failed", failure.get());
    }

    private static final class Actions implements PlaybackSessionPlayer.Callback {
        int next;
        int previous;
        int stops;
        List<MediaItem> items;
        long startPosition;
        @Override public TimeshiftPlayback timeshift() { return null; }
        @Override public void previous() { previous++; }
        @Override public void next() { next++; }
        @Override public void stop() { stops++; }
        @Override public void setMediaItems(List<MediaItem> items, int index, long start) {
            this.items = items;
            startPosition = start;
        }
    }

    private static final class FixturePlayer extends SimpleBasePlayer {
        final boolean live;
        long position = 30_000;
        int seeks;
        int stops;
        int replacements;

        FixturePlayer(boolean live) {
            super(Looper.getMainLooper());
            this.live = live;
        }

        @Override protected State getState() {
            MediaItemData.Builder item = new MediaItemData.Builder("single-business-item")
                    .setMediaItem(new MediaItem.Builder().setUri("https://example.com/media").build())
                    .setDurationUs(live ? C.TIME_UNSET : 120_000_000)
                    .setIsDynamic(live).setIsSeekable(!live);
            if (live) item.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().build());
            return new State.Builder().setPlaylist(List.of(item.build())).setCurrentMediaItemIndex(0)
                    .setContentPositionMs(() -> position).setPlaybackState(Player.STATE_READY)
                    .setAvailableCommands(new Player.Commands.Builder().addAll(
                            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                            Player.COMMAND_STOP, Player.COMMAND_RELEASE).build()).build();
        }

        @Override protected ListenableFuture<?> handleSeek(int item, long position, int command) {
            this.position = position == C.TIME_UNSET ? 0 : position;
            seeks++;
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items, int index, long position) {
            replacements++;
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleStop() {
            stops++;
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleRelease() { return Futures.immediateVoidFuture(); }
    }
}
