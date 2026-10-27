package com.fongmi.android.tv.ui.custom;

import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.player.PlaybackSpecs;
import com.fongmi.android.tv.player.PlayerManager;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One presentation per new media request, independent of buffering and pause/resume events. */
public final class PlaybackSpecsController {
    private final JetStreamPlaybackSpecsView view;
    private final PlaybackSpecsBackdrop backdrop;
    private final Supplier<PlayerManager> player;
    private final Supplier<String> rating;
    private final BooleanSupplier unobstructed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private boolean started;
    private boolean pending;
    private int attempts;
    private int obstructedAttempts;

    public PlaybackSpecsController(JetStreamPlaybackSpecsView view, PlayerView surface,
                                   Supplier<PlayerManager> player, Supplier<String> rating,
                                   BooleanSupplier unobstructed) {
        this.view = view;
        this.player = player;
        this.rating = rating;
        this.unobstructed = unobstructed;
        backdrop = new PlaybackSpecsBackdrop(view, surface);
    }

    public void begin() {
        dismiss();
        pending = true;
        attempts = 0;
        obstructedAttempts = 0;
    }

    public boolean isPending() {
        return pending;
    }

    public void setStarted(boolean started) {
        this.started = started;
        if (!started) dismiss();
    }

    /** Called by playing/track/size events, with one short delay to coalesce decoder metadata. */
    public void onMetadataChanged() {
        if (!started || !pending && !view.isActive()) return;
        obstructedAttempts = 0;
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 120);
    }

    private void refresh() {
        if (!started || !pending && !view.isActive()) return;
        PlayerManager manager = player.get();
        if (manager == null || manager.isReleased() || !manager.isPlaying()) return;
        if (!unobstructed.getAsBoolean()) {
            // A sidebar's exit animation remains VISIBLE until its final frame.
            if (pending && ++obstructedAttempts < 6) main.postDelayed(refresh, 300);
            return;
        }
        PlaybackSpecs specs = manager.getPlaybackSpecs();
        if (specs.isEmpty()) {
            if (pending && ++attempts < 5) main.postDelayed(refresh, 300);
            return;
        }
        String contentRating = rating.get();
        if (pending) {
            pending = false;
            view.show(specs.getVideoTitle(), specs.getVideoDetail(), specs.getAudioTitle(),
                    specs.getAudioDetail(), contentRating);
            backdrop.start();
        } else if (view.isActive()) {
            view.update(specs.getVideoTitle(), specs.getVideoDetail(), specs.getAudioTitle(),
                    specs.getAudioDetail(), contentRating);
        }
    }

    public void dismiss() {
        pending = false;
        main.removeCallbacks(refresh);
        backdrop.stop();
        view.dismiss();
    }

    /** Keep a running presentation when a preview becomes fullscreen; only recapture its crop. */
    public void setFullscreen(boolean fullscreen) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        float density = view.getResources().getDisplayMetrics().density;
        int left = Math.round((fullscreen ? 48 : 14) * density);
        int top = Math.round((fullscreen ? 28 : 14) * density);
        if (params.leftMargin == left && params.topMargin == top) return;
        params.setMargins(left, top, left, 0);
        view.setLayoutParams(params);
        if (view.isActive()) backdrop.start();
    }
}
