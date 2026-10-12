package com.lody.virtual.server.pm.parser;

import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.Build;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.util.List;

/** Restores only an APK-declared, max-SDK-filtered legacy capability. Never grants it. */
final class LegacyWriteManifestCompatibility {
    static final String WRITE = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final int MAX_MANIFEST_EVENTS = 32_768;

    private LegacyWriteManifestCompatibility() { }

    static boolean shouldRestore(int sdk, String expectedPackage, String manifestPackage,
                                 String tag, String permission, int maxSdk) {
        return sdk <= 32 && sdk > maxSdk && maxSdk >= 0
                && expectedPackage != null && expectedPackage.equals(manifestPackage)
                && WRITE.equals(permission)
                && ("uses-permission".equals(tag)
                    || sdk >= 23 && "uses-permission-sdk-23".equals(tag));
    }

    static void augment(VPackage pkg, File baseApk) {
        if (Build.VERSION.SDK_INT > 32 || pkg == null || pkg.requestedPermissions == null
                || pkg.requestedPermissions.contains(WRITE)) return;
        if (declaresFilteredWrite(baseApk, pkg.packageName, Build.VERSION.SDK_INT)) {
            pkg.requestedPermissions.add(WRITE);
        }
    }

    private static boolean declaresFilteredWrite(File apk, String expectedPackage, int sdk) {
        if (apk == null || !apk.isFile()) return false;
        AssetManager assets = null;
        XmlResourceParser manifest = null;
        try {
            assets = mirror.android.content.res.AssetManager.ctor.newInstance();
            Integer cookie = mirror.android.content.res.AssetManager.addAssetPath.call(assets,
                    apk.getAbsolutePath());
            if (cookie == null || cookie <= 0) return false;
            manifest = assets.openXmlResourceParser(cookie, "AndroidManifest.xml");
            String manifestPackage = null;
            boolean found = false;
            for (int events = 0; events < MAX_MANIFEST_EVENTS; events++) {
                int event = manifest.next();
                if (event == XmlPullParser.END_DOCUMENT) return found;
                if (event != XmlPullParser.START_TAG) continue;
                if (manifest.getDepth() == 1) {
                    if (!"manifest".equals(manifest.getName())) return false;
                    manifestPackage = manifest.getAttributeValue(null, "package");
                    if (!expectedPackage.equals(manifestPackage)) return false;
                } else if (manifest.getDepth() == 2) {
                    String permission = manifest.getAttributeValue(ANDROID, "name");
                    if (!WRITE.equals(permission)) continue;
                    String tag = manifest.getName();
                    if (!"uses-permission".equals(tag) && !"uses-permission-sdk-23".equals(tag)) continue;
                    // Missing/resource/invalid maxima do not prove platform max-SDK filtering.
                    int maximum = manifest.getAttributeIntValue(ANDROID, "maxSdkVersion", -1);
                    found |= shouldRestore(sdk, expectedPackage, manifestPackage, tag, permission, maximum);
                }
            }
            return false;
        } catch (Throwable unavailable) {
            return false;
        } finally {
            try { if (manifest != null) manifest.close(); } catch (Throwable ignored) { }
            try { if (assets != null) assets.close(); } catch (Throwable ignored) { }
        }
    }

    /** Revalidated APK membership replaces only WRITE; all other cached permissions are retained. */
    static void synchronizeWriteMembership(List<String> cached, List<String> validated, int sdk) {
        if (sdk > 32 || cached == null) return;
        boolean requested = validated != null && validated.contains(WRITE);
        if (!requested) cached.removeAll(java.util.Collections.singleton(WRITE));
        else if (!cached.contains(WRITE)) cached.add(WRITE);
    }
}
