package com.lody.virtual.client;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;

import mirror.android.app.LoadedApk;
import mirror.android.content.pm.ApplicationInfoN;

/** Exposes canonical guest-private paths after native I/O redirects have been installed. */
final class GuestDataPathMapper {

    private GuestDataPathMapper() {
    }

    static Paths create(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("packageName must not be empty");
        }
        return new Paths(
                "/data/user/0/" + packageName,
                "/data/user_de/0/" + packageName);
    }

    static void apply(ApplicationInfo applicationInfo) {
        Paths paths = create(applicationInfo.packageName);
        applicationInfo.dataDir = paths.credentialProtectedDataDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                ApplicationInfoN.credentialEncryptedDataDir.set(
                        applicationInfo, paths.credentialProtectedDataDir);
                ApplicationInfoN.deviceEncryptedDataDir.set(
                        applicationInfo, paths.deviceProtectedDataDir);
            }
            ApplicationInfoN.credentialProtectedDataDir.set(
                    applicationInfo, paths.credentialProtectedDataDir);
            ApplicationInfoN.deviceProtectedDataDir.set(
                    applicationInfo, paths.deviceProtectedDataDir);
        }
    }

    static void applyToLoadedApk(Object loadedApk, ApplicationInfo applicationInfo) {
        Paths paths = create(applicationInfo.packageName);
        apply(applicationInfo);
        if (LoadedApk.mDataDir != null) {
            LoadedApk.mDataDir.set(loadedApk, paths.credentialProtectedDataDir);
        }
        if (LoadedApk.mDataDirFile != null) {
            LoadedApk.mDataDirFile.set(
                    loadedApk, new File(paths.credentialProtectedDataDir));
        }
        if (LoadedApk.mCredentialProtectedDataDirFile != null) {
            LoadedApk.mCredentialProtectedDataDirFile.set(
                    loadedApk, new File(paths.credentialProtectedDataDir));
        }
        if (LoadedApk.mDeviceProtectedDataDirFile != null) {
            LoadedApk.mDeviceProtectedDataDirFile.set(
                    loadedApk, new File(paths.deviceProtectedDataDir));
        }
    }

    static final class Paths {
        final String credentialProtectedDataDir;
        final String deviceProtectedDataDir;

        Paths(String credentialProtectedDataDir, String deviceProtectedDataDir) {
            this.credentialProtectedDataDir = credentialProtectedDataDir;
            this.deviceProtectedDataDir = deviceProtectedDataDir;
        }
    }
}
