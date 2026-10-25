package com.fongmi.android.tv.service;

import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the latest asynchronous navigation request for a playback service.
 *
 * <p>A request is valid only while its generation is current and its source
 * media identity still matches the player.  Keeping this state separate from
 * Media3 makes the stale-result rule deterministic and unit-testable.</p>
 */
public final class NavigationRequestGate {

    private final AtomicLong generation = new AtomicLong();
    private Future<?> task;

    public synchronized Request begin(String mediaId, String playbackKey) {
        Request request = new Request(generation.incrementAndGet(), mediaId, playbackKey);
        cancelTask();
        return request;
    }

    public synchronized void invalidate() {
        generation.incrementAndGet();
        cancelTask();
    }

    /** A stale request must cancel its actual source work, including pending login dialogs. */
    public synchronized void attach(Request request, Future<?> task) {
        if (!isCurrent(request)) {
            task.cancel(true);
            return;
        }
        if (this.task != task) cancelTask();
        this.task = task;
    }

    private void cancelTask() {
        Future<?> previous = task;
        task = null;
        if (previous != null) previous.cancel(true);
    }

    public long snapshot() {
        return generation.get();
    }

    public boolean isCurrent(long snapshot) {
        return snapshot == generation.get();
    }

    public boolean isCurrent(Request request) {
        return request != null && request.generation() == generation.get();
    }

    public boolean isCurrent(Request request, String mediaId, String playbackKey) {
        return isCurrent(request)
                && Objects.equals(request.mediaId(), mediaId)
                && Objects.equals(request.playbackKey(), playbackKey);
    }

    public record Request(long generation, String mediaId, String playbackKey) {
    }
}
