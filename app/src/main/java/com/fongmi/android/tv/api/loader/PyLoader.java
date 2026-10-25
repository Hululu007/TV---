package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.CallTimeout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        Spider created = spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader.spider(api);
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                if (CallTimeout.currentDeniedDialog() || CallTimeout.currentHasDialog() || Thread.currentThread().isInterrupted()) return null;
                e.printStackTrace();
                return new SpiderNull();
            }
        });
        return created != null ? created : new SpiderNull();
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
