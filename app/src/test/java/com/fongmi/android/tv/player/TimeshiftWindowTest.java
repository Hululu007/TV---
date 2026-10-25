package com.fongmi.android.tv.player;

import com.fongmi.android.tv.bean.Timeshift;
import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.*;

public class TimeshiftWindowTest {

    private static final long NOW = 1_792_944_000_000L;

    @Test public void shouldParseProviderMillisecondsAndEncodedPlaceholder() {
        Timeshift config = new Gson().fromJson("{\"url\":\"https://example.com/replay?t=%7btime%7d\","
                + "\"start\":" + (NOW - 90_000) + ",\"window\":60000}", Timeshift.class);
        assertTrue(config.isValid(NOW));
        assertEquals("https://example.com/replay?t=" + NOW, config.getUrl(NOW));
        assertEquals("https://example.com/replay?t=0", config.getUrl(0));
        assertEquals(NOW - 60_000, config.getStart(NOW));
    }

    @Test public void shouldRejectMissingPlaceholderOrNonHttpResolver() {
        assertFalse(new Timeshift("https://example.com/replay", NOW - 1000, 1000).isValid(NOW));
        assertFalse(new Timeshift("file:///replay/{time}", NOW - 1000, 1000).isValid(NOW));
        assertFalse(new Timeshift("javascript:{time}", NOW - 1000, 1000).isValid(NOW));
        assertFalse(new Timeshift(null, NOW - 1000, 1000).isValid(NOW));
    }

    @Test public void shouldRejectInvalidStartOrWindow() {
        for (long start : new long[]{-1, 0, NOW, NOW + 1}) {
            assertFalse(new Timeshift("https://example.com/{time}", start, 1000).isValid(NOW));
        }
        for (long length : new long[]{-1, 0}) {
            assertFalse(new Timeshift("https://example.com/{time}", NOW - 1000, length).isValid(NOW));
        }
    }

    @Test public void shouldGrowUntilTheProviderWindowIsFull() {
        TimeshiftWindow window = new TimeshiftWindow(new Timeshift("https://example.com/{time}", NOW - 10_000, 60_000));
        assertEquals(NOW - 10_000, window.start(NOW));
        assertEquals(10_000, window.duration(NOW));
        assertEquals(40_000, window.duration(NOW + 30_000));
        assertEquals(60_000, window.duration(NOW + 90_000));
        assertEquals(NOW + 30_000, window.start(NOW + 90_000));
    }

    @Test public void shouldKeepEpochPositionWhenTheWindowMoves() {
        TimeshiftWindow window = window();
        assertEquals(30_000, window.position(NOW - 30_000, NOW));
        assertEquals(20_000, window.position(NOW - 30_000, NOW + 10_000));
        assertEquals(0, window.position(NOW - 30_000, NOW + 60_000));
    }

    @Test public void shouldClampPositionsOutsideTheAvailableWindow() {
        TimeshiftWindow window = window();
        assertEquals(0, window.position(Long.MIN_VALUE, NOW));
        assertEquals(60_000, window.position(Long.MAX_VALUE, NOW));
        assertEquals(60_000, window.position(NOW, NOW));
    }

    @Test public void shouldResolveReplayAndExplicitLiveEdgeWithoutSecondsConversion() {
        TimeshiftWindow window = window();
        assertEquals(NOW - 60_000, window.seekTime(-1, NOW));
        assertEquals(NOW - 60_000, window.seekTime(0, NOW));
        assertEquals(NOW - 10_123, window.seekTime(49_877, NOW));
        assertEquals(0, window.seekTime(60_000, NOW));
        assertEquals(0, window.seekTime(Long.MAX_VALUE, NOW));
    }

    @Test public void shouldNotExposeNegativeDurationAfterClockMovesBeforeProviderStart() {
        TimeshiftWindow window = window();
        assertEquals(0, window.duration(NOW - 180_000));
        assertEquals(0, window.seekTime(0, NOW - 180_000));
    }

    private TimeshiftWindow window() {
        return new TimeshiftWindow(new Timeshift("https://example.com/{time}", NOW - 120_000, 60_000));
    }
}
