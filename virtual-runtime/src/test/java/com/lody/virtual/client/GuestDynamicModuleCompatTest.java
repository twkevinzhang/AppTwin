package com.lody.virtual.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class GuestDynamicModuleCompatTest {

    @Test
    public void preloadsFacebookLiteMsysModule() throws Exception {
        List<String[]> calls = new ArrayList<>();

        GuestDynamicModuleCompat.preloadKnownModules(
                "com.facebook.lite",
                (manager, provider, load, module) -> calls.add(
                        new String[]{manager, provider, load, module}));

        assertEquals(1, calls.size());
        assertArrayEquals(new String[]{"X.0KM", "A00", "A05", "msys"}, calls.get(0));
    }

    @Test
    public void doesNotPreloadModulesForOtherGuests() throws Exception {
        List<String[]> calls = new ArrayList<>();

        GuestDynamicModuleCompat.preloadKnownModules(
                "jp.naver.line.android",
                (manager, provider, load, module) -> calls.add(
                        new String[]{manager, provider, load, module}));

        assertEquals(0, calls.size());
    }
}
