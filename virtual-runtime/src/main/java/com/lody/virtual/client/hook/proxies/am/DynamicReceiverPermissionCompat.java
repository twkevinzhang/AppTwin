package com.lody.virtual.client.hook.proxies.am;

/**
 * Bridges AndroidX's API 32-and-lower non-exported dynamic receiver permission through the host.
 */
final class DynamicReceiverPermissionCompat {
    static final String SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";

    private DynamicReceiverPermissionCompat() {
    }

    static boolean isSyntheticPermission(String permission) {
        return permission != null && permission.endsWith(SUFFIX);
    }

    static String forHost(String hostPackage) {
        if (hostPackage == null || hostPackage.isEmpty()) {
            throw new IllegalArgumentException("hostPackage must not be empty");
        }
        return hostPackage + SUFFIX;
    }
}
