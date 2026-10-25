package com.fongmi.android.tv.bean;

import com.google.gson.annotations.SerializedName;

import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/** A provider's sliding replay window. All timestamps and durations are milliseconds. */
public final class Timeshift {

    private static final Pattern TIME = Pattern.compile("\\{time\\}|%7Btime%7D", Pattern.CASE_INSENSITIVE);

    @SerializedName("url")
    private String url;
    @SerializedName("start")
    private long start;
    @SerializedName("window")
    private long window;

    public Timeshift() {
    }

    public Timeshift(String url, long start, long window) {
        this.url = url;
        this.start = start;
        this.window = window;
    }

    public boolean isValid(long now) {
        return url != null && TIME.matcher(url).find() && start > 0 && start < now && window > 0
                && HttpUrl.parse(getUrl(now)) != null;
    }

    public long getStart(long now) {
        return Math.max(start, now - window);
    }

    public String getUrl(long time) {
        return TIME.matcher(url).replaceAll(String.valueOf(time));
    }
}
