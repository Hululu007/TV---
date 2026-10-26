package com.fongmi.android.tv.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable facts about the selected input streams; missing metadata stays absent. */
public final class PlaybackSpecs {

    public static final PlaybackSpecs EMPTY = new PlaybackSpecs("", "", "", "", "");
    private static final Pattern PIXEL_DEPTH = Pattern.compile("(?:p|gray|gbrp|gbrap)(9|10|12|14|16)(?:le|be)?$");

    private final String videoTitle;
    private final String videoDetail;
    private final String audioTitle;
    private final String audioDetail;
    private final String contentRating;

    private PlaybackSpecs(String videoTitle, String videoDetail, String audioTitle,
                          String audioDetail, String contentRating) {
        this.videoTitle = videoTitle;
        this.videoDetail = videoDetail;
        this.audioTitle = audioTitle;
        this.audioDetail = audioDetail;
        this.contentRating = contentRating;
    }

    public String getVideoTitle() { return videoTitle; }
    public String getVideoDetail() { return videoDetail; }
    public String getAudioTitle() { return audioTitle; }
    public String getAudioDetail() { return audioDetail; }
    public String getContentRating() { return contentRating; }
    public boolean hasVideo() { return !videoTitle.isEmpty() || !videoDetail.isEmpty(); }
    public boolean hasAudio() { return !audioTitle.isEmpty() || !audioDetail.isEmpty(); }
    public boolean isEmpty() { return !hasVideo() && !hasAudio(); }

    /** Only a caller with explicit catalogue certification metadata should supply this value. */
    public PlaybackSpecs withContentRating(@Nullable String rating) {
        return new PlaybackSpecs(videoTitle, videoDetail, audioTitle, audioDetail, clean(rating));
    }

    public static PlaybackSpecs fromFormats(@Nullable Format video, @Nullable Format audio) {
        String range = "";
        int bitDepth = Format.NO_VALUE;
        if (video != null) {
            ColorInfo color = video.colorInfo;
            if (color != null) {
                range = switch (color.colorTransfer) {
                    case C.COLOR_TRANSFER_SDR -> "SDR";
                    case C.COLOR_TRANSFER_ST2084 -> "HDR";
                    case C.COLOR_TRANSFER_HLG -> "HLG";
                    default -> "";
                };
                bitDepth = color.lumaBitdepth;
            }
            if (bitDepth <= 0) bitDepth = codecBitDepth(video.codecs);
            if (isDolbyVision(video)) range = "Dolby Vision";
        }
        return create(video, audio, range, bitDepth, "");
    }

    /** MPV properties here describe decoder input, never video-out-params or audio-out-params. */
    public static PlaybackSpecs fromMpv(@Nullable Format video, @Nullable Format audio,
                                        @Nullable String transfer, @Nullable String pixelFormat,
                                        @Nullable String channelLayout) {
        String range = switch (normalized(transfer)) {
            case "bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8" -> "SDR";
            case "pq", "smpte2084" -> "HDR";
            case "hlg", "arib-std-b67" -> "HLG";
            default -> "";
        };
        if (video != null && isDolbyVision(video)) range = "Dolby Vision";
        return create(video, audio, range, pixelBitDepth(pixelFormat), clean(channelLayout));
    }

    @Nullable
    public static Format selectedFormat(Tracks tracks, int type) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i);
            }
        }
        return null;
    }

    private static PlaybackSpecs create(@Nullable Format video, @Nullable Format audio,
                                        String range, int bitDepth, String channelLayout) {
        String title = video == null ? "" : join(resolution(video), range);
        String detail = video == null ? "" : join(videoCodec(video), bitDepth > 0 ? bitDepth + "bit" : "");
        return new PlaybackSpecs(title, detail, audio == null ? "" : audioCodec(audio),
                audio == null ? "" : channels(audio.channelCount, channelLayout), "");
    }

    private static String resolution(Format video) {
        // Use the actual raster. A cropped stream or portrait source must not claim another size.
        if (video.height <= 0) return "";
        int height = video.width > 0 ? Math.min(video.width, video.height) : video.height;
        return height + "P";
    }

    private static boolean isDolbyVision(Format video) {
        String codecs = normalized(video.codecs);
        return "video/dolby-vision".equals(video.sampleMimeType) || codecs.startsWith("dvhe")
                || codecs.startsWith("dvh1") || codecs.startsWith("dva1") || codecs.startsWith("dvav");
    }

    private static String videoCodec(Format video) {
        String codec = normalized(video.codecs);
        String mime = normalized(video.sampleMimeType);
        if (codec.startsWith("dvhe") || codec.startsWith("dvh1")) return "H.265";
        if (codec.startsWith("dva1") || codec.startsWith("dvav")) return "H.264";
        if (codec.startsWith("avc1") || codec.startsWith("avc3") || codec.equals("h264")) return "H.264";
        if (codec.startsWith("hev1") || codec.startsWith("hvc1") || codec.equals("hevc") || codec.equals("h265")) return "H.265";
        if (codec.startsWith("av01") || codec.equals("av1")) return "AV1";
        if (codec.startsWith("vp09") || codec.equals("vp9")) return "VP9";
        return switch (mime) {
            case "video/avc" -> "H.264";
            case "video/hevc" -> "H.265";
            case "video/av01" -> "AV1";
            case "video/x-vnd.on2.vp9" -> "VP9";
            case "video/x-vnd.on2.vp8" -> "VP8";
            case "video/mpeg2" -> "MPEG-2";
            case "video/mp4v-es" -> "MPEG-4";
            case "video/mjpeg" -> "MJPEG";
            default -> "";
        };
    }

    private static String audioCodec(Format audio) {
        String codec = normalized(audio.codecs);
        if (codec.startsWith("pcm_") || codec.equals("pcm")) return "PCM";
        if (codec.startsWith("mp4a.40") || codec.equals("aac")) return "AAC";
        return switch (normalized(audio.sampleMimeType)) {
            case "audio/mp4a-latm" -> "AAC";
            case "audio/ac3" -> "AC-3";
            case "audio/eac3" -> "E-AC-3";
            case "audio/eac3-joc" -> "Dolby Atmos";
            case "audio/vnd.dts" -> "DTS";
            case "audio/vnd.dts.hd" -> "DTS-HD";
            case "audio/true-hd" -> "TrueHD";
            case "audio/flac" -> "FLAC";
            case "audio/mpeg" -> "MP3";
            case "audio/mpeg-l1" -> "MP1";
            case "audio/mpeg-l2" -> "MP2";
            case "audio/opus" -> "Opus";
            case "audio/vorbis" -> "Vorbis";
            case "audio/raw" -> "PCM";
            case "audio/alac" -> "ALAC";
            case "audio/ac4" -> "AC-4";
            default -> "";
        };
    }

    private static String channels(int count, String layout) {
        String normalized = normalized(layout);
        String known = switch (normalized) {
            case "mono", "1.0" -> "1.0";
            case "stereo", "2.0" -> "2.0";
            case "2.1", "3.0", "3.1", "4.0", "4.1", "5.0", "5.1", "6.1", "7.1" -> normalized;
            case "5.1(side)" -> "5.1";
            case "7.1(wide)", "7.1(wide-side)" -> "7.1";
            default -> "";
        };
        if (!known.isEmpty()) return known;
        return switch (count) {
            case 1 -> "1.0";
            case 2 -> "2.0";
            default -> count > 0 ? count + "ch" : "";
        };
    }

    private static int pixelBitDepth(@Nullable String pixelFormat) {
        String format = normalized(pixelFormat);
        Matcher matcher = PIXEL_DEPTH.matcher(format);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        if (format.equals("p010le") || format.equals("p010be")) return 10;
        if (format.equals("p012le") || format.equals("p012be")) return 12;
        if (format.equals("p016le") || format.equals("p016be")) return 16;
        return switch (format) {
            case "yuv420p", "yuv422p", "yuv444p", "yuv410p", "yuv411p", "yuv440p",
                    "yuvj420p", "yuvj422p", "yuvj444p", "nv12", "nv21", "rgb24", "bgr24",
                    "rgba", "bgra", "argb", "abgr", "gbrp", "gray", "gray8" -> 8;
            default -> Format.NO_VALUE;
        };
    }

    private static int codecBitDepth(@Nullable String codecs) {
        String[] parts = normalized(codecs).split("\\.");
        // These AVC profiles permit only 8-bit samples. High 10/4:2:2/4:4:4 permit
        // several depths, so their profile number alone is deliberately insufficient.
        if (parts.length == 2 && (parts[0].equals("avc1") || parts[0].equals("avc3"))
                && parts[1].matches("[0-9a-f]{6}")) {
            int profile = Integer.parseInt(parts[1].substring(0, 2), 16);
            if (profile == 66 || profile == 77 || profile == 88 || profile == 100) return 8;
        }
        if (parts.length >= 4 && (parts[0].equals("av01") || parts[0].equals("vp09"))) {
            return switch (parts[3]) {
                case "08" -> 8;
                case "10" -> 10;
                case "12" -> 12;
                default -> Format.NO_VALUE;
            };
        }
        return Format.NO_VALUE;
    }

    private static String join(String left, String right) {
        return left.isEmpty() ? right : right.isEmpty() ? left : left + " " + right;
    }

    private static String normalized(@Nullable String value) {
        return clean(value).toLowerCase(Locale.US);
    }

    private static String clean(@Nullable String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
