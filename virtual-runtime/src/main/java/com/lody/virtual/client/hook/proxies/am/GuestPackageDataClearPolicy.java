package com.lody.virtual.client.hook.proxies.am;

final class GuestPackageDataClearPolicy {
    private GuestPackageDataClearPolicy() {
    }

    static boolean shouldHandle(String requestedPackage, String callingPackage) {
        return requestedPackage != null && requestedPackage.equals(callingPackage);
    }
}
