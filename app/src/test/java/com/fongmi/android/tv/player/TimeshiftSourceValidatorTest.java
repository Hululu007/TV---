package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class TimeshiftSourceValidatorTest {

    @Test public void shouldAcceptExistingDirectMediaTransportsAndUris() {
        for (String url : new String[]{"https://example.com/replay.m3u8", "http://127.0.0.1:8080/live",
                "rtsp://user:pass@example.com:554/live", "rtsps://example.com/live", "rtmp://example.com/app/stream",
                "rtmps://example.com/app/stream", "udp://@239.0.0.1:1234", "rtp://239.0.0.1:1234",
                "tcp://[::1]:1234", "srt://example.com:9000?mode=caller", "smb://server/share/movie.mkv",
                "file:///storage/emulated/0/replay.mp4", "content://media/external/video/media/1",
                "asset:///demo.mp4", "rawresource:///123", "android.resource://com.example/raw/demo",
                "data:application/vnd.apple.mpegurl;base64,I0VYVE0zVQo="}) {
            assertTrue(url, TimeshiftSourceValidator.accepts(url, null, Map.of("Referer", "https://example.com/")));
        }
    }

    @Test public void shouldRejectRelativeMalformedAndNonMediaUrls() {
        for (String url : new String[]{"", "not-a-media-url", "/relative/live.m3u8", "//example.com/live",
                " https://example.com/live", "https://example.com/live ", "https:/missing-host", "http://", "https://example.com:invalid/live", "https://example.com:99999/live",
                "rtsp:opaque", "rtsp://", "rtsp://example.com:bad/live", "rtsp://[invalid]/live",
                "javascript:alert(1)", "intent://media#Intent;scheme=http;end", "mailto:media@example.com",
                "file:relative.mp4", "file:///", "content:///video/1", "asset:relative.mp4",
                "data:text/html,<html>", "data:video/mp4;base64,", "data:video/mp4;base64,invalid!",
                "rawresource:///not-an-id", "rawresource:///99999999999", "https://example.com/line\nbreak"}) {
            assertFalse(url, TimeshiftSourceValidator.accepts(url, null, Map.of()));
        }
    }

    @Test public void shouldRejectImagesBeforeReplacingPlayback() {
        assertFalse(TimeshiftSourceValidator.accepts("https://example.com/poster.jpg", null, Map.of()));
        assertFalse(TimeshiftSourceValidator.accepts("https://example.com/media", "image/png", Map.of()));
        assertFalse(TimeshiftSourceValidator.accepts("data:image/png;base64,aGVsbG8=", null, Map.of()));
    }

    @Test public void shouldRejectHeadersThatTheActualHttpClientCannotSend() {
        assertFalse(TimeshiftSourceValidator.accepts("https://example.com/live", null, Map.of("X-Test", "a\r\nb")));
        assertFalse(TimeshiftSourceValidator.accepts("https://example.com/live", null, Map.of("Bad\nName", "value")));
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Test", null);
        assertFalse(TimeshiftSourceValidator.accepts("https://example.com/live", null, headers));
        assertTrue(TimeshiftSourceValidator.accepts("https://example.com/live", null, Map.of("Authorization", "Bearer fixture")));
    }
}
