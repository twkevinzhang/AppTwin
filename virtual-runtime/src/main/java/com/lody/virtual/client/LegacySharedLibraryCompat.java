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
    private static final File LOCATION_PROVIDER =
            new File("/system/framework/com.android.location.provider.jar");

    private LegacySharedLibraryCompat() {
    }

    static String android12DelegatePath() {
        return buildDelegatePath(
                ANDROID_TEST_BASE, APACHE_HTTP_BOOT, APACHE_HTTP, LOCATION_PROVIDER);
    }

    static String buildDelegatePath(
            File testBase, File apacheBoot, File apacheFallback, File locationProvider) {
        List<String> paths = new ArrayList<>(3);
        if (testBase.isFile()) {
            paths.add(testBase.getAbsolutePath());
        }
        File apache = apacheBoot.isFile() ? apacheBoot : apacheFallback;
        if (apache.isFile()) {
            paths.add(apache.getAbsolutePath());
        }
        if (locationProvider.isFile()) {
            paths.add(locationProvider.getAbsolutePath());
        }
        if (paths.isEmpty()) {
            return "";
        }
        return String.join(File.pathSeparator, paths);
    }
}
