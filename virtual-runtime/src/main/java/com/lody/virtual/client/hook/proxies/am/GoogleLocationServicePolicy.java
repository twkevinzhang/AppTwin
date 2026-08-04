package com.lody.virtual.client.hook.proxies.am;

/** Prevents location-denied Google guests from keeping fused-location workers alive. */
public final class GoogleLocationServicePolicy {
    private static final String GMS_PACKAGE = "com.google.android.gms";

    private GoogleLocationServicePolicy() {
    }

    public static boolean shouldBlock(String packageName, String serviceName, String action,
                                      boolean hasLocationPermission) {
        if (hasLocationPermission || !GMS_PACKAGE.equals(packageName)) {
            return false;
        }
        return isLocationName(serviceName) || isLocationName(action);
    }

    private static boolean isLocationName(String value) {
        return value != null
                && (value.startsWith("com.google.android.location.")
                || value.startsWith("com.google.android.gms.location.")
                || value.startsWith("com.google.android.gms.semanticlocation.")
                || value.contains(".location.internal.")
                || value.contains(".semanticlocation."));
    }
}
