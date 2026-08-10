package com.lody.virtual.client;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.lody.virtual.remote.InstalledAppInfo;

import java.util.Arrays;

import mirror.android.content.pm.ApplicationInfoL;

/**
 * Gives guest code a package-owned view of its APK paths while retaining the host-private
 * activated revision as the physical backing store.
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

        applicationInfo.sourceDir = mapping.guestBasePath;
        applicationInfo.publicSourceDir = mapping.guestBasePath;
        applicationInfo.splitSourceDirs = copyOrNull(mapping.guestSplitPaths);
        applicationInfo.splitPublicSourceDirs = copyOrNull(mapping.guestSplitPaths);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (ApplicationInfoL.scanSourceDir != null) {
                ApplicationInfoL.scanSourceDir.set(applicationInfo, mapping.guestBasePath);
            }
            if (ApplicationInfoL.scanPublicSourceDir != null) {
                ApplicationInfoL.scanPublicSourceDir.set(applicationInfo, mapping.guestBasePath);
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
