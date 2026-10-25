package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.fongmi.quickjs.utils.QuickLog;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.CallTimeout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private static final String TAG = JsLoader.class.getSimpleName();
    private static final int PREVIEW_LIMIT = 240;

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader(BuildConfig.TMDB_API_KEY);
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
        QuickLog.d(TAG, "setRecent key=%s", recent);
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider cached = spiders.get(key);
        if (cached != null) {
            QuickLog.d(TAG, "getSpider cache hit key=%s api=%s", key, api);
            return cached;
        }
        Spider created = spiders.computeIfAbsent(key, k -> {
            long start = System.currentTimeMillis();
            QuickLog.d(TAG, "getSpider start key=%s api=%s ext=%s jar=%s", key, api, preview(ext), preview(jar));
            try {
                Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = key;
                spider.init(App.get(), ext);
                QuickLog.d(TAG, "getSpider success key=%s elapsed=%sms", key, System.currentTimeMillis() - start);
                return spider;
            } catch (Throwable e) {
                if (CallTimeout.currentDeniedDialog() || CallTimeout.currentHasDialog() || Thread.currentThread().isInterrupted()) return null;
                QuickLog.e(TAG, "getSpider failed key=" + key + " api=" + api + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
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

    private static String preview(String text) {
        if (text == null) return "null";
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= PREVIEW_LIMIT ? text : text.substring(0, PREVIEW_LIMIT) + "...";
    }
}
