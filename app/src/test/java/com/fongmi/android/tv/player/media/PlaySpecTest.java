package com.fongmi.android.tv.player.media;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaySpecTest {

    @Test
    public void shouldBuildAReplacementWithoutChangingTheActiveSource() {
        PlaySpec original = PlaySpec.from("channel", "https://example.com/live.m3u8", Map.of("Referer", "original"), null);
        PlaySpec replay = original.withSource("https://example.com/replay.m3u8", "application/x-mpegURL", Map.of("Referer", "replay"));

        assertEquals("https://example.com/live.m3u8", original.getUrl());
        assertEquals("original", original.getHeaders().get("Referer"));
        assertEquals("channel", replay.getKey());
        assertEquals("https://example.com/replay.m3u8", replay.getUrl());
        assertEquals("replay", replay.getHeaders().get("Referer"));
    }

    @Test
    public void shouldRemoveStaticRangeWithoutMutatingSourceHeaders() {
        Map<String, String> source = new HashMap<>();
        source.put("range", "bytes=100-");
        source.put("Referer", "https://example.com/");

        Map<String, String> sanitized = PlaySpec.sanitizeHeaders(source);

        assertFalse(sanitized.keySet().stream().anyMatch("Range"::equalsIgnoreCase));
        assertEquals("https://example.com/", sanitized.get("Referer"));
        assertTrue(source.containsKey("range"));
    }
}
