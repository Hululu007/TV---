package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PlaybackSpecsTest {

    @Test
    public void shouldDescribeKnownInputVideoAndAudio() {
        Format video = video("video/avc", "avc1.640028", 1920, 1080).buildUpon()
                .setColorInfo(new ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_SDR)
                        .setLumaBitdepth(8).setChromaBitdepth(8).build()).build();
        PlaybackSpecs specs = PlaybackSpecs.fromFormats(video, audio("audio/mp4a-latm", 2));

        assertEquals("1080P SDR", specs.getVideoTitle());
        assertEquals("H.264 8bit", specs.getVideoDetail());
        assertEquals("AAC", specs.getAudioTitle());
        assertEquals("2.0", specs.getAudioDetail());
        assertTrue(specs.hasVideo());
        assertTrue(specs.hasAudio());
    }

    @Test
    public void shouldOmitUnknownColorAndBitDepth() {
        PlaybackSpecs specs = PlaybackSpecs.fromFormats(video("video/hevc", "hevc", 1920, 800), null);
        assertEquals("800P", specs.getVideoTitle());
        assertEquals("H.265", specs.getVideoDetail());
        assertFalse(specs.hasAudio());
    }

    @Test
    public void shouldReadBitDepthOnlyFromUnambiguousCodecFields() {
        assertEquals("H.264 8bit", PlaybackSpecs.fromFormats(video("video/avc", "avc1.4D401F", 1280, 720), null).getVideoDetail());
        assertEquals("H.264", PlaybackSpecs.fromFormats(video("video/avc", "avc1.6E001F", 1280, 720), null).getVideoDetail());
        assertEquals("AV1 10bit", PlaybackSpecs.fromFormats(video("video/av01", "av01.0.08M.10", 3840, 2160), null).getVideoDetail());
        assertEquals("VP9 12bit", PlaybackSpecs.fromFormats(video("video/x-vnd.on2.vp9", "vp09.02.10.12", 3840, 2160), null).getVideoDetail());
    }

    @Test
    public void shouldNotInferLfeFromChannelCount() {
        PlaybackSpecs specs = PlaybackSpecs.fromFormats(null, audio("audio/eac3", 6));
        assertEquals("6ch", specs.getAudioDetail());
        assertEquals("E-AC-3", specs.getAudioTitle());
        assertFalse(specs.hasVideo());
    }

    @Test
    public void shouldUseExplicitMpvLayoutAndInputPixelDepth() {
        PlaybackSpecs specs = PlaybackSpecs.fromMpv(video("video/hevc", "hevc", 3840, 2160),
                audio("audio/eac3", 6), "pq", "yuv420p10le", "5.1(side)");
        assertEquals("2160P HDR", specs.getVideoTitle());
        assertEquals("H.265 10bit", specs.getVideoDetail());
        assertEquals("5.1", specs.getAudioDetail());
    }

    @Test
    public void shouldNotTreatHardwarePixelFormatOrAutoGammaAsFacts() {
        PlaybackSpecs specs = PlaybackSpecs.fromMpv(video("video/hevc", "hevc", 1920, 1080),
                audio("audio/vnd.dts", 8), "auto", "mediacodec", "unknown8");
        assertEquals("1080P", specs.getVideoTitle());
        assertEquals("H.265", specs.getVideoDetail());
        assertEquals("8ch", specs.getAudioDetail());
    }

    @Test
    public void shouldExposePcmAndCorrectMpegAudioLayers() {
        assertEquals("PCM", PlaybackSpecs.fromFormats(null, audio("audio/raw", 2)).getAudioTitle());
        assertEquals("MP1", PlaybackSpecs.fromFormats(null, audio("audio/mpeg-L1", 2)).getAudioTitle());
        assertEquals("MP2", PlaybackSpecs.fromFormats(null, audio("audio/mpeg-L2", 2)).getAudioTitle());
        assertEquals("MP3", PlaybackSpecs.fromFormats(null, audio("audio/mpeg", 2)).getAudioTitle());
    }

    @Test
    public void shouldDistinguishHlgAndDolbyVisionOnlyWhenDeclared() {
        Format hlg = video("video/hevc", "hevc", 1920, 1080).buildUpon()
                .setColorInfo(new ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build()).build();
        assertEquals("1080P HLG", PlaybackSpecs.fromFormats(hlg, null).getVideoTitle());
        PlaybackSpecs dolby = PlaybackSpecs.fromFormats(video("video/dolby-vision", "dvhe.08.06", 3840, 2160), null);
        assertEquals("2160P Dolby Vision", dolby.getVideoTitle());
        assertEquals("H.265", dolby.getVideoDetail());
    }

    @Test
    public void shouldReadOnlySelectedTracks() {
        Format alternate = audio("audio/eac3", 6);
        Format selected = audio("audio/mp4a-latm", 2);
        Tracks tracks = new Tracks(List.of(new Tracks.Group(new TrackGroup(alternate, selected),
                false, new int[]{C.FORMAT_HANDLED, C.FORMAT_HANDLED}, new boolean[]{false, true})));
        assertSame(selected, PlaybackSpecs.selectedFormat(tracks, C.TRACK_TYPE_AUDIO));
        assertEquals(null, PlaybackSpecs.selectedFormat(tracks, C.TRACK_TYPE_VIDEO));
    }

    @Test
    public void shouldKeepCertificationExplicitAndImmutable() {
        PlaybackSpecs original = PlaybackSpecs.fromFormats(null, audio("audio/raw", 2));
        PlaybackSpecs rated = original.withContentRating(" PG-13 ");
        assertEquals("", original.getContentRating());
        assertEquals("PG-13", rated.getContentRating());
        assertEquals("", rated.withContentRating(null).getContentRating());
        assertEquals(original.getAudioTitle(), rated.getAudioTitle());
        assertTrue(PlaybackSpecs.EMPTY.withContentRating("PG").isEmpty());
    }

    @Test
    public void shouldKeepAbsentStreamsEmpty() {
        PlaybackSpecs specs = PlaybackSpecs.fromFormats(null, null);
        assertTrue(specs.isEmpty());
        assertEquals("", specs.getVideoTitle());
        assertEquals("", specs.getAudioTitle());
    }

    private static Format video(String mime, String codecs, int width, int height) {
        return new Format.Builder().setSampleMimeType(mime).setCodecs(codecs)
                .setWidth(width).setHeight(height).build();
    }

    private static Format audio(String mime, int channels) {
        return new Format.Builder().setSampleMimeType(mime).setChannelCount(channels).build();
    }
}
