package com.lody.virtual.helper.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

public class ComponentUtilsTest {
    @Test
    public void googleSystemPackagesRemainOutsideTheVirtualRuntime() {
        ApplicationInfo gms = new ApplicationInfo();
        gms.packageName = "com.google.android.gms";
        gms.flags = ApplicationInfo.FLAG_SYSTEM;

        assertTrue(ComponentUtils.isSystemApp(gms));
    }

    @Test
    public void ordinaryUserAppsRemainVirtualRuntimeCandidates() {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "com.example.app";

        assertFalse(ComponentUtils.isSystemApp(app));
    }
}
