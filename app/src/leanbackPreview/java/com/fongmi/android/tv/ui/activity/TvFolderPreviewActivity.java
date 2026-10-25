package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityVodBinding;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.ui.fragment.TypeFragment;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Isolated host for the production folder/TypeFragment navigation and request pipeline. */
public final class TvFolderPreviewActivity extends VodActivity {
    public static final String ROOT_ID = "preview-folder-root";
    public static final String FILTER_OPTION = "Folder filter option A";
    private static final String SITE_KEY = "preview-folder-source";
    private static final String SOURCE_URL = "https://tv-folder-fixture.invalid/api";
    private static Session fixture;
    public static volatile TvFolderPreviewActivity current;

    @Override
    protected ViewBinding getBinding() {
        ActivityVodBinding binding = ActivityVodBinding.inflate(getLayoutInflater());
        binding.title.setText("Folder filter navigation");
        ViewGroup parent = (ViewGroup) binding.pager.getParent();
        int index = parent.indexOfChild(binding.pager);
        ViewGroup.LayoutParams params = binding.pager.getLayoutParams();
        FrameLayout container = new FrameLayout(this);
        container.setId(R.id.pager);
        parent.removeView(binding.pager);
        parent.addView(container, index, params);
        return binding;
    }

    @Override
    protected void initView(Bundle state) {
        if (fixture == null) throw new IllegalStateException("Folder fixture session is missing");
        current = this;
        if (folder() == null) {
            FolderFragment folder = FolderFragment.newInstance(SITE_KEY, fixture.type);
            getSupportFragmentManager().beginTransaction().replace(R.id.pager, folder).commit();
        }
    }

    @Override protected void initEvent() { }

    @Override
    public void closeFilter() {
        if (folder() != null) showFilters(false);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    public FolderFragment folder() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.pager);
        return fragment instanceof FolderFragment folder ? folder : null;
    }

    public TypeFragment currentCategory() {
        FolderFragment folder = folder();
        if (folder == null) return null;
        Fragment fragment = folder.getChildFragmentManager().findFragmentById(R.id.container);
        return fragment instanceof TypeFragment category ? category : null;
    }

    public String currentCategoryId() {
        TypeFragment category = currentCategory();
        return category == null ? null : category.requireArguments().getString("typeId");
    }

    public void showFilters(boolean visible) {
        folder().getType().setFilter(visible);
        folder().toggleFilter(visible);
    }

    public static Session openFixture(Context context) {
        if (!BuildConfig.DEBUG || !context.getPackageName().endsWith(".preview")) {
            throw new IllegalStateException("Folder fixtures require the isolated preview application");
        }
        if (fixture != null) throw new IllegalStateException("Folder fixture is already open");
        fixture = new Session();
        return fixture;
    }

    public static final class Session implements AutoCloseable {
        private final OkHttpClient previousClient = OkHttp.client();
        private final Object previousSites = HomePreviewFixtures.field(VodConfig.get(), "sites");
        private final Map<String, List<Filter>> cache = filterCache();
        private final List<Filter> previousFilters = cache.get(ROOT_ID);
        private final Class type = Class.objectFrom("{\"type_id\":\"" + ROOT_ID + "\",\"type_name\":\"Folder root\",\"type_flag\":\"1\"}");
        public final List<JsonObject> requests = new CopyOnWriteArrayList<>();

        private Session() {
            type.setFilters(Filter.arrayFrom("[{\"key\":\"region\",\"name\":\"Region\",\"value\":[{\"n\":\"All regions\",\"v\":\"\"},{\"n\":\"" + FILTER_OPTION + "\",\"v\":\"A\"}]}]"));
            Result result = new Result();
            result.setTypes(List.of(type));
            new Cache().put(result);
            List<Site> sites = new ArrayList<>(VodConfig.get().getSites());
            sites.removeIf(site -> SITE_KEY.equals(site.getKey()));
            Site site = App.gson().fromJson("{\"key\":\"" + SITE_KEY + "\",\"name\":\"Folder fixture\",\"type\":1,\"api\":\"" + SOURCE_URL + "\"}", Site.class);
            sites.add(site);
            HomePreviewFixtures.setField(VodConfig.get(), "sites", sites);
            OkHttpClient offline = previousClient.newBuilder().addInterceptor(chain -> {
                if (!chain.request().url().host().equals("tv-folder-fixture.invalid")) {
                    throw new IOException("Intentional offline folder fixture");
                }
                String category = chain.request().url().queryParameter("t");
                JsonObject request = new JsonObject();
                request.addProperty("category", category);
                String filters = chain.request().url().queryParameter("f");
                request.add("filters", filters == null ? new JsonObject() : App.gson().fromJson(filters, JsonObject.class));
                requests.add(request);
                JsonObject item = new JsonObject();
                item.addProperty("vod_id", category + "-child");
                item.addProperty("vod_name", "Entry in " + category);
                item.addProperty("vod_tag", "folder");
                JsonObject payload = new JsonObject();
                payload.addProperty("page", 1);
                payload.addProperty("pagecount", 1);
                payload.addProperty("limit", 20);
                payload.addProperty("total", 1);
                payload.add("list", App.gson().toJsonTree(List.of(item)));
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(ResponseBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8")))
                        .build();
            }).build();
            HomePreviewFixtures.setField(OkHttp.get(), "client", offline);
        }

        public boolean requested(String category, String region) {
            return requests.stream().anyMatch(request -> category.equals(request.get("category").getAsString())
                    && (region == null || request.getAsJsonObject("filters").has("region")
                    && region.equals(request.getAsJsonObject("filters").get("region").getAsString())));
        }

        @Override
        public void close() {
            HomePreviewFixtures.setField(OkHttp.get(), "client", previousClient);
            HomePreviewFixtures.setField(VodConfig.get(), "sites", previousSites);
            if (previousFilters == null) cache.remove(ROOT_ID);
            else cache.put(ROOT_ID, previousFilters);
            fixture = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Filter>> filterCache() {
        try {
            java.lang.Class<?> loader = java.lang.Class.forName(Cache.class.getName() + "$Loader");
            Field instance = loader.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            Field values = Cache.class.getDeclaredField("cache");
            values.setAccessible(true);
            return (Map<String, List<Filter>>) values.get(instance.get(null));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot preserve folder fixture filters", error);
        }
    }
}
