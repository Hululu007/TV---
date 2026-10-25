package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.JetStreamPageBackgroundView;
import com.fongmi.android.tv.ui.custom.JetStreamPageRootLayout;
import com.fongmi.android.tv.ui.custom.JetStreamVodControlView;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/** Preview-only fixture using the production TV controls and real Media3 state notifications. */
public final class TimeshiftPreviewActivity extends BaseActivity implements JetStreamVodControlView.Listener {

    public JetStreamVodControlView controls;
    public volatile int returnLiveCount;
    public volatile int seekCount;
    public volatile long lastSeek;
    private FrameLayout root;
    private FixturePlayer player;

    @Override protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration config = new android.content.res.Configuration();
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override protected ViewBinding getBinding() {
        root = new JetStreamPageRootLayout(this);
        return () -> root;
    }

    @Override protected boolean customWall() { return false; }

    @Override protected void initView(Bundle state) {
        root.addView(new JetStreamPageBackgroundView(this), new FrameLayout.LayoutParams(-1, -1));
        player = new FixturePlayer();
        controls = new JetStreamVodControlView(this);
        controls.setListener(this);
        controls.setPlayer(player);
        controls.setLiveMode(true);
        controls.setTimeshiftEnabled(true);
        controls.setTransportActions(false, false, false);
        controls.setTopActions(false, false, false);
        controls.setPlaybackState(false, false);
        controls.setMediaTitle("直播时移 · 验证频道", "可回看最近一小时", "");
        controls.setControlsVisible(true);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        controls.requestFocus();
    }

    public void setSupported(boolean supported) {
        player.supported = supported;
        player.refresh();
        controls.setTimeshiftEnabled(supported);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return controls != null && controls.consumeSeekConfirmKey(event) || super.dispatchKeyEvent(event);
    }

    @Override public void onSeekTo(long positionMs) {
        lastSeek = positionMs;
        seekCount++;
        player.position = positionMs;
        player.refresh();
    }

    @Override public void onCommand(String key) {
        if (!"timeshift_live".equals(key)) return;
        returnLiveCount++;
        player.position = FixturePlayer.DURATION;
        player.refresh();
    }

    @Override public void onPlayPause() {}
    @Override public void onPrevious() {}
    @Override public void onNext() {}
    @Override public void onRepeat() {}
    @Override public void onCommandLongClick(String key) {}
    @Override public void onShowControls() {}

    @Override protected void onDestroy() {
        if (controls != null) controls.setPlayer(null);
        if (player != null) player.release();
        super.onDestroy();
    }

    private static final class FixturePlayer extends SimpleBasePlayer {
        static final long DURATION = 3_600_000;
        long position = 2_100_000;
        boolean supported = true;

        FixturePlayer() { super(Looper.getMainLooper()); }
        void refresh() { invalidateState(); }

        @Override protected State getState() {
            return new State.Builder().setAvailableCommands(new Player.Commands.Builder().addAll(
                            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_RELEASE).build())
                    .setPlaylist(List.of(new MediaItemData.Builder("timeshift-ui-preview")
                            .setMediaItem(new MediaItem.Builder().setUri("https://example.com/live.m3u8").build())
                            .setDurationUs(supported ? DURATION * 1000 : 0).setIsSeekable(supported)
                            .setIsDynamic(true).build()))
                    .setCurrentMediaItemIndex(0).setPlaybackState(Player.STATE_READY)
                    .setContentPositionMs(() -> supported ? position : 0).build();
        }

        @Override protected ListenableFuture<?> handleRelease() { return Futures.immediateVoidFuture(); }
    }
}
