package com.fongmi.android.tv.service;

import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.fongmi.android.tv.player.TimeshiftPlayback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/** Exposes the playback timeline while keeping episode/channel navigation owned by the service. */
public final class PlaybackSessionPlayer extends ForwardingSimpleBasePlayer {

    private final Callback callback;

    public PlaybackSessionPlayer(Player player, Callback callback) {
        super(player);
        this.callback = callback;
    }

    public void replacePlayer(Player player) {
        setPlayer(player);
    }

    public void refreshTimeline() {
        invalidateState();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items, int startIndex, long startPositionMs) {
        callback.setMediaItems(items, startIndex, startPositionMs);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int command) {
        // BasePlayer still delivers navigation commands with INDEX_UNSET for a single-item
        // timeline. Those mean another application episode/channel, not a playlist item.
        if (command == COMMAND_SEEK_TO_PREVIOUS || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) callback.previous();
        else if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) callback.next();
        else if (callback.timeshift() != null && callback.timeshift().isActive()) return callback.timeshift().seekTo(positionMs);
        else return super.handleSeek(mediaItemIndex, positionMs, command);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        callback.stop();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected State getState() {
        State state = super.getState();
        if (callback.timeshift() != null) state = callback.timeshift().getState(state);
        return state.buildUpon().setAvailableCommands(state.availableCommands.buildUpon()
                .addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD,
                        COMMAND_STOP, COMMAND_SET_REPEAT_MODE).build()).build();
    }

    public interface Callback {
        TimeshiftPlayback timeshift();

        void setMediaItems(List<MediaItem> items, int startIndex, long startPositionMs);

        void previous();

        void next();

        void stop();
    }
}
