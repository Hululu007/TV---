package com.fongmi.android.tv.player;

import com.fongmi.android.tv.player.media.MediaUrlGuard;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Base64;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.HttpUrl;

/** Validates resolver output before it is allowed to replace a working media item. */
final class TimeshiftSourceValidator {

    private TimeshiftSourceValidator() {
    }

    static boolean accepts(String url, String format, Map<String, String> headers) {
        if (!isMediaUri(url) || MediaUrlGuard.shouldReject(url, format)) return false;
        try {
            Headers.Builder builder = new Headers.Builder();
            if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) builder.add(entry.getKey(), entry.getValue());
            builder.build();
            return true;
        } catch (RuntimeException invalidHeader) {
            return false;
        }
    }

    private static boolean isMediaUri(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        String url = input.trim();
        if (!url.equals(input)) return false;
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute()) return false;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return switch (scheme) {
                case "http", "https" -> hasAuthority(uri) && HttpUrl.parse(url) != null;
                // These are stream transports used by the existing Exo/MPV data sources.
                // HttpUrl supplies the same host/IPv6/port validation without rewriting playback.
                case "rtsp", "rtsps", "rtmp", "rtmps", "rtp", "udp", "tcp", "tls", "ftp", "sftp", "smb", "srt", "rist" ->
                        hasAuthority(uri) && HttpUrl.parse("http" + url.substring(url.indexOf(':'))) != null;
                case "file" -> hasAbsolutePath(uri) && (uri.getAuthority() == null || uri.getAuthority().isEmpty() || "localhost".equalsIgnoreCase(uri.getAuthority()));
                case "content", "android.resource" -> !uri.isOpaque() && uri.getAuthority() != null && !uri.getAuthority().isEmpty() && hasAbsolutePath(uri);
                case "asset" -> !uri.isOpaque() && hasAbsolutePath(uri);
                case "rawresource" -> !uri.isOpaque() && isResourceId(uri.getPath());
                case "data" -> isMediaData(uri.getSchemeSpecificPart());
                default -> false;
            };
        } catch (URISyntaxException | RuntimeException invalidUrl) {
            return false;
        }
    }

    private static boolean hasAuthority(URI uri) {
        return !uri.isOpaque() && uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty();
    }

    private static boolean hasAbsolutePath(URI uri) {
        String path = uri.getPath();
        return path != null && path.startsWith("/") && path.length() > 1;
    }

    private static boolean isResourceId(String path) {
        if (path == null || !path.matches("/[1-9][0-9]{0,9}")) return false;
        return Long.parseLong(path.substring(1)) <= Integer.MAX_VALUE;
    }

    private static boolean isMediaData(String data) {
        int comma = data.indexOf(',');
        if (comma <= 0 || comma == data.length() - 1) return false;
        String metadata = data.substring(0, comma).toLowerCase(Locale.ROOT);
        if (metadata.contains(";base64") && Base64.getDecoder().decode(data.substring(comma + 1).replaceAll("\\s", "")).length == 0) return false;
        String mime = metadata.split(";", 2)[0];
        return mime.startsWith("audio/") || mime.startsWith("video/") || mime.equals("application/vnd.apple.mpegurl")
                || mime.equals("application/x-mpegurl") || mime.equals("application/dash+xml") || mime.equals("application/octet-stream");
    }
}
