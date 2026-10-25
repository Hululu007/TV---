package com.fongmi.android.tv.api.loader;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.Map;
import static org.junit.Assert.*;

/** Runs without Android/Python native initialization; only a Python source may start its runtime. */
public class PyLoaderInitializationTest {
    @Test public void constructingClearingAndEmptyProxyDoNotInitializePython() throws Exception {
        PyLoader loader = new PyLoader();
        Field runtime = PyLoader.class.getDeclaredField("loader");
        runtime.setAccessible(true);
        assertNull(runtime.get(loader));
        loader.setRecent("no-python-source-loaded");
        assertNull(loader.proxy(Map.of()));
        loader.clear();
        loader.clear();
        assertNull(runtime.get(loader));
        assertNull(loader.proxy(Map.of()));
    }
}
