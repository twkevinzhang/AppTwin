package com.lody.virtual.client.hook.providers;

/** Keeps virtual-provider identity logical while physical providers retain host attribution. */
final class ProviderAttributionIdentityPolicy {

    private ProviderAttributionIdentityPolicy() {
    }

    static String packageName(boolean externalProvider, String guestPackage, String hostPackage) {
        if (externalProvider || guestPackage == null || guestPackage.length() == 0) {
            return hostPackage;
        }
        return guestPackage;
    }
}
