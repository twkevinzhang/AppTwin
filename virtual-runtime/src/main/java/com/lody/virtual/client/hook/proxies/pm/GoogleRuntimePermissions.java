package com.lody.virtual.client.hook.proxies.pm;

/** Narrow virtual permission policy used while bootstrapping original Google guest packages. */
public final class GoogleRuntimePermissions {

    static final String GOOGLE_SERVICES_FRAMEWORK = "com.google.android.gsf";
    static final String READ_DEVICE_CONFIG = "android.permission.READ_DEVICE_CONFIG";

    private GoogleRuntimePermissions() {
    }

    public static boolean shouldGrant(String guestPackage, String permission) {
        return GOOGLE_SERVICES_FRAMEWORK.equals(guestPackage)
                && READ_DEVICE_CONFIG.equals(permission);
    }
}
