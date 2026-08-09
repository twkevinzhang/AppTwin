package com.lody.virtual.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Builds the delegate class path needed by guest apps on Android 12. */
final class LegacySharedLibraryCompat {
    private static final File ANDROID_TEST_BASE =
            new File("/system/framework/android.test.base.jar");
    private static final File APACHE_HTTP_BOOT =
            new File("/system/framework/org.apache.http.legacy.boot.jar");
    private static final File APACHE_HTTP =
            new File("/system/framework/org.apache.http.legacy.jar");
    private LegacySharedLibraryCompat() {
    }

    static String android12DelegatePath() {
        return buildDelegatePath(ANDROID_TEST_BASE, APACHE_HTTP_BOOT, APACHE_HTTP);
    }

    static String buildDelegatePath(File testBase, File apacheBoot, File apacheFallback) {
        List<String> paths = new ArrayList<>(2);
        if (testBase.isFile()) {
            paths.add(testBase.getAbsolutePath());
        }
        File apache = apacheBoot.isFile() ? apacheBoot : apacheFallback;
        if (apache.isFile()) {
            paths.add(apache.getAbsolutePath());
        }
        if (paths.isEmpty()) {
            return "";
        }
        return paths.size() == 1
                ? paths.get(0)
                : paths.get(0) + File.pathSeparator + paths.get(1);
    }
}
