package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer.MediaItemData;
import androidx.media3.common.SimpleBasePlayer.PositionSupplier;
import androidx.media3.common.SimpleBasePlayer.State;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Timeshift;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.MediaUrlGuard;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.github.catvod.net.OkHttp;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;

/** Provider-backed seek resolution shared by Exo and MPV. Accessed on the player's looper. */
public final class TimeshiftPlayback {

    private final Host owner;
    private final Clock clock;
    private final HandlerWrapper handler;
    private final TimeshiftRequest requests;
    private final Timeline.Window sourceWindow = new Timeline.Window();
    private final Runnable update = this::updateWindow;
    private Timeshift config;
    private TimeshiftWindow window;
    private MediaItem mediaItem;
    private Object uid;
    private SettableFuture<Void> pending;
    private long now;
    private long epochOffset = C.TIME_UNSET;
    private boolean replaying;
    private boolean awaitingReady;

    public TimeshiftPlayback(Host owner) {
        this(owner, OkHttp.client().newBuilder().callTimeout(15, TimeUnit.SECONDS).build(), Clock.DEFAULT);
    }

    public TimeshiftPlayback(Host owner, Call.Factory requests, Clock clock) {
        this.owner = owner;
        this.clock = clock;
        this.handler = clock.createHandler(owner.getPlayer().getApplicationLooper(), null);
        this.requests = new TimeshiftRequest(requests, handler::post);
    }

    public void start(PlaySpec spec) {
        clear();
        Timeshift candidate = spec.getTimeshift();
        long now = clock.currentTimeMillis();
        try {
            if (candidate == null || !candidate.isValid(now)) return;
            MediaItem item = MediaItemFactory.from(spec);
            config = candidate;
            window = new TimeshiftWindow(candidate);
            mediaItem = item;
            uid = new Object();
            awaitingReady = true;
            updateWindow();
        } catch (RuntimeException invalidConfig) {
            clear();
        }
    }

    public void clear() {
        cancel();
        handler.removeCallbacks(update);
        boolean active = isActive();
        config = null;
        window = null;
        mediaItem = null;
        uid = null;
        epochOffset = C.TIME_UNSET;
        replaying = false;
        awaitingReady = false;
        if (active) owner.onTimeshiftChanged();
    }

    private void cancel() {
        requests.cancel();
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    public void cancelPending() {
        cancel();
    }

    public boolean isActive() {
        return config != null;
    }

    public boolean isReplaying() {
        return isActive() && replaying;
    }

    void updateWindow() {
        handler.removeCallbacks(update);
        if (!isActive()) return;
        now = clock.currentTimeMillis();
        owner.onTimeshiftChanged();
        handler.postDelayed(update, 1000);
    }

    public long getDuration() {
        return isActive() ? window.duration(now) : 0;
    }

    public void onReady() {
        if (!isActive() || !awaitingReady) return;
        awaitingReady = false;
        if (epochOffset == C.TIME_UNSET) epochOffset = clock.currentTimeMillis() - owner.getPlayer().getCurrentPosition();
        owner.onTimeshiftChanged();
    }

    public long getPosition() {
        return position(owner.getPlayer().getCurrentPosition());
    }

    private long position(long position) {
        if (!isActive()) return position;
        if (awaitingReady) return replaying ? window.position(epochOffset, now) : getDuration();
        Player player = owner.getPlayer();
        Timeline timeline = player.getCurrentTimeline();
        if (replaying && !timeline.isEmpty()) {
            timeline.getWindow(player.getCurrentMediaItemIndex(), sourceWindow);
            if (sourceWindow.windowStartTimeMs != C.TIME_UNSET) {
                return window.position(sourceWindow.windowStartTimeMs + position, now);
            }
        }
        if (epochOffset == C.TIME_UNSET) {
            if (player.getPlaybackState() != Player.STATE_READY) return getDuration();
            epochOffset = clock.currentTimeMillis() - player.getCurrentPosition();
        }
        return window.position(epochOffset + position, now);
    }

    /** A stable single-item timeline keeps controllers attached when the resolver changes URLs. */
    public State getState(State state) {
        if (!isActive() || state.getPlaylist().size() != 1) return state;
        MediaItemData item = state.getPlaylist().get(0).buildUpon().setUid(uid).setMediaItem(mediaItem)
                .setLiveConfiguration(MediaItem.LiveConfiguration.UNSET).setIsDynamic(true).setIsSeekable(true)
                .setWindowStartTimeMs(window.start(now)).setDefaultPositionUs(Util.msToUs(getDuration()))
                .setDurationUs(Util.msToUs(getDuration())).setPositionInFirstPeriodUs(Util.msToUs(window.start(now)))
                .setPeriods(List.of()).build();
        State.Builder result = state.buildUpon().setPlaylist(List.of(item)).setCurrentMediaItemIndex(0)
                .setAvailableCommands(state.availableCommands.buildUpon().addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build())
                .setContentPositionMs(getPosition())
                .setContentBufferedPositionMs(PositionSupplier.getConstant(position(owner.getPlayer().getBufferedPosition())));
        if (state.hasPositionDiscontinuity) {
            result.setPositionDiscontinuity(state.positionDiscontinuityReason, position(state.discontinuityPositionMs));
        }
        return result.build();
    }

    public ListenableFuture<?> seekTo(long position) {
        if (!isActive()) return Futures.immediateVoidFuture();
        cancel();
        // Refresh the moving window at the gesture's commit time, not its last UI tick.
        boolean liveEdge = position == C.TIME_UNSET || position >= getDuration();
        now = clock.currentTimeMillis();
        long time = liveEdge ? 0 : window.seekTime(position, now);
        SettableFuture<Void> future = SettableFuture.create();
        pending = future;
        future.addListener(() -> {
            if (future.isCancelled() && pending == future) cancel();
        }, handler::post);
        Futures.addCallback(requests.resolve(config.getUrl(time)), new FutureCallback<>() {
            @Override
            public void onSuccess(String body) {
                if (pending != future) return;
                try {
                    Result result = Result.fromJson(body);
                    if (result.hasMsg() || result.needParse() || result.getRealUrl().isEmpty()
                            || MediaUrlGuard.shouldReject(result.getRealUrl(), result.getFormat())) {
                        fail(future);
                        return;
                    }
                    apply(future, result, time);
                } catch (RuntimeException invalidResponse) {
                    fail(future);
                }
            }

            @Override
            public void onFailure(Throwable failure) {
                fail(future);
            }
        }, handler::post);
        return future;
    }

    private void apply(SettableFuture<Void> future, Result result, long time) {
        if (pending != future) return;
        boolean previousReplay = replaying;
        boolean previousAwaiting = awaitingReady;
        long previousOffset = epochOffset;
        replaying = time != 0;
        awaitingReady = true;
        epochOffset = replaying ? time : C.TIME_UNSET;
        try {
            owner.applyTimeshift(result.getRealUrl(), result.getFormat(), result.getHeader());
        } catch (RuntimeException rejectedSource) {
            replaying = previousReplay;
            awaitingReady = previousAwaiting;
            epochOffset = previousOffset;
            fail(future);
            return;
        }
        pending = null;
        try {
            owner.onTimeshiftChanged();
        } catch (RuntimeException detachedController) {
            // Playback has already changed; a detached UI must not leave its seek pending.
        } finally {
            future.set(null);
        }
    }

    private void fail(SettableFuture<Void> future) {
        if (pending != future) return;
        pending = null;
        try {
            owner.onTimeshiftError();
        } catch (RuntimeException detachedController) {
            // Error presentation can outlive its UI; the unchanged stream still owns playback.
        } finally {
            // Do not raise a player error or stop the stream when resolving another position fails.
            future.set(null);
        }
    }

    public interface Host {
        Player getPlayer();

        void applyTimeshift(String url, String format, Map<String, String> headers);

        void onTimeshiftChanged();

        void onTimeshiftError();
    }
}
