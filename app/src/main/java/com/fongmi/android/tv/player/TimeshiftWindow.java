package com.fongmi.android.tv.player;

import com.fongmi.android.tv.bean.Timeshift;

/** Converts the moving, zero-based control timeline to provider epoch milliseconds. */
public final class TimeshiftWindow {

    private final Timeshift config;

    public TimeshiftWindow(Timeshift config) {
        this.config = config;
    }

    public long start(long now) {
        return Math.min(now, config.getStart(now));
    }

    public long duration(long now) {
        return Math.max(0, now - start(now));
    }

    public long position(long epoch, long now) {
        if (epoch <= start(now)) return 0;
        if (epoch >= now) return duration(now);
        return epoch - start(now);
    }

    /** A zero epoch is the provider's explicit "return to live" request. */
    public long seekTime(long position, long now) {
        long duration = duration(now);
        return position >= duration ? 0 : start(now) + Math.max(0, position);
    }
}
