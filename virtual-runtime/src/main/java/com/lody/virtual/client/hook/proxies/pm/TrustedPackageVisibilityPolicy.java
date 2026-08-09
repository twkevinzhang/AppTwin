package com.lody.virtual.client.hook.proxies.pm;

/** Trusted GmsCore is virtual-only and must never fall back to the physical ROM package. */
public final class TrustedPackageVisibilityPolicy {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String GSF_PACKAGE = "com.google.android.gsf";

    private TrustedPackageVisibilityPolicy() {
    }

    public static boolean allowPhysicalFallback(String packageName) {
        return !isReserved(packageName);
    }

    public static boolean isReserved(String packageName) {
        return GMS_PACKAGE.equals(packageName)
                || PLAY_STORE_PACKAGE.equals(packageName)
                || GSF_PACKAGE.equals(packageName);
    }

    static boolean isAvailableToVirtualUser(
            String packageName, boolean virtuallyInstalled, boolean physicallyInstalled) {
        return isReserved(packageName) ? virtuallyInstalled
                : virtuallyInstalled || physicallyInstalled;
    }

    static boolean allowSupplementalGids(String packageName, boolean virtuallyInstalled) {
        return !isReserved(packageName) || virtuallyInstalled;
    }
}
