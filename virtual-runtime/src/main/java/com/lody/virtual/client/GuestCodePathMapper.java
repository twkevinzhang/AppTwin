package com.lody.virtual.client;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.lody.virtual.remote.InstalledAppInfo;

import java.util.Arrays;

import mirror.android.content.pm.ApplicationInfoL;

/**
 * Keeps compatibility redirects for package-owned APK paths while exposing real, readable
 * paths to Android runtime components that bypass the virtual I/O layer.
 */
final class GuestCodePathMapper {

    private static final String DATA_APP_ROOT = "/data/app/";

    private GuestCodePathMapper() {
    }

    static Mapping create(String packageName, String actualBasePath, String[] actualSplitPaths) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("packageName must not be empty");
        }
        if (actualBasePath == null || actualBasePath.isEmpty()) {
            throw new IllegalArgumentException("actualBasePath must not be empty");
        }
        String guestRoot = DATA_APP_ROOT + packageName;
        String[] physicalSplits = actualSplitPaths == null
                ? new String[0] : Arrays.copyOf(actualSplitPaths, actualSplitPaths.length);
        String[] guestSplits = new String[physicalSplits.length];
        for (int index = 0; index < guestSplits.length; index++) {
            guestSplits[index] = guestRoot + "/split_" + index + ".apk";
        }
        return new Mapping(
                actualBasePath,
                guestRoot + "/base.apk",
                physicalSplits,
                guestSplits);
    }

    static void apply(ApplicationInfo applicationInfo, InstalledAppInfo installedAppInfo) {
        Mapping mapping = create(
                applicationInfo.packageName,
                installedAppInfo.apkPath,
                installedAppInfo.splitCodePaths);
        NativeEngine.redirectFile(mapping.guestBasePath, mapping.physicalBasePath);
        for (int index = 0; index < mapping.guestSplitPaths.length; index++) {
            NativeEngine.redirectFile(
                    mapping.guestSplitPaths[index],
                    mapping.physicalSplitPaths[index]);
        }

        // Native linker namespaces and app-side java.io.File checks do not consistently pass
        // through NativeEngine's path redirection. Supplying synthetic /data/app paths here
        // consequently makes installed split APKs appear missing. These files are private to
        // the AppTwin host UID and are directly readable by the guest process it owns.
        applicationInfo.sourceDir = mapping.physicalBasePath;
        applicationInfo.publicSourceDir = mapping.physicalBasePath;
        applicationInfo.splitSourceDirs = copyOrNull(mapping.physicalSplitPaths);
        applicationInfo.splitPublicSourceDirs = copyOrNull(mapping.physicalSplitPaths);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (ApplicationInfoL.scanSourceDir != null) {
                ApplicationInfoL.scanSourceDir.set(applicationInfo, mapping.physicalBasePath);
            }
            if (ApplicationInfoL.scanPublicSourceDir != null) {
                ApplicationInfoL.scanPublicSourceDir.set(applicationInfo, mapping.physicalBasePath);
            }
        }
    }

    private static String[] copyOrNull(String[] paths) {
        return paths.length == 0 ? null : Arrays.copyOf(paths, paths.length);
    }

    static final class Mapping {
        final String physicalBasePath;
        final String guestBasePath;
        final String[] physicalSplitPaths;
        final String[] guestSplitPaths;

        Mapping(String physicalBasePath, String guestBasePath,
                String[] physicalSplitPaths, String[] guestSplitPaths) {
            this.physicalBasePath = physicalBasePath;
            this.guestBasePath = guestBasePath;
            this.physicalSplitPaths = physicalSplitPaths;
            this.guestSplitPaths = guestSplitPaths;
        }
    }
}
