package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import dalvik.system.DexClassLoader;

public class BaseLoader {

    private final JarLoader jarLoader;
    private final PyLoader pyLoader;
    private final JsLoader jsLoader;

    private BaseLoader() {
        jarLoader = new JarLoader();
        pyLoader = new PyLoader();
        jsLoader = new JsLoader();
    }

    public static BaseLoader get() {
        return Loader.INSTANCE;
    }

    private static boolean isJs(String api) {
        return !TextUtils.isEmpty(api) && api.contains(".js");
    }

    private static boolean isForward(String api) {
        return !TextUtils.isEmpty(api) && api.contains(".fwd");
    }

    private static boolean isPy(String api) {
        return !TextUtils.isEmpty(api) && api.contains(".py");
    }

    private static boolean isCsp(String api) {
        return !TextUtils.isEmpty(api) && api.startsWith("csp_");
    }

    public void clear() {
        Task.execute(() -> {
            jarLoader.clear();
            pyLoader.clear();
            jsLoader.clear();
        });
    }

    public Spider getSpider(String key, int type, String api, String ext, String jar) {
        if (isPy(api)) return pyLoader.getSpider(key, api, ext);
        else if (isCsp(api)) return jarLoader.getSpider(key, api, ext, jar);
        else if (type == 3 || isJs(api) || isForward(api)) return jsLoader.getSpider(key, api, ext, jar);
        else return new SpiderNull();
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        return getSpider(key, 0, api, ext, jar);
    }

    public Spider getSpider(String key) {
        Site site = VodConfig.get().getSite(key);
        Live live = LiveConfig.get().getLive(key);
        if (!site.isEmpty()) return site.spider();
        if (!live.isEmpty()) return live.spider();
        return new SpiderNull();
    }

    public void setRecent(String key, int type, String api, String jar) {
        if (isPy(api)) pyLoader.setRecent(key);
        else if (isCsp(api)) jarLoader.setRecent(Util.md5(jar));
        else if (type == 3 || isJs(api) || isForward(api)) jsLoader.setRecent(key);
    }

    public void setRecent(String key, String api, String jar) {
        setRecent(key, 0, api, jar);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        // Media fetch/proxy workers cannot present login UI over active playback.
        return com.github.catvod.utils.CallTimeout.withoutDialogs(() -> proxyWithoutDialog(params));
    }

    private Object[] proxyWithoutDialog(Map<String, String> params) throws Exception {
        if (params.containsKey("siteKey")) return getSpider(params.get("siteKey")).proxy(params);
        if ("js".equals(params.get("do"))) return jsLoader.proxy(params);
        if ("py".equals(params.get("do"))) return pyLoader.proxy(params);
        return jarLoader.proxy(params);
    }

    public void parseJar(String jar, boolean recent) {
        if (TextUtils.isEmpty(jar)) return;
        String key = Util.md5(jar);
        jarLoader.parseJar(key, jar);
        if (recent) jarLoader.setRecent(key);
    }

    public DexClassLoader dex(String jar) {
        return jarLoader.dex(jar);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    private static class Loader {
        static volatile BaseLoader INSTANCE = new BaseLoader();
    }
}
