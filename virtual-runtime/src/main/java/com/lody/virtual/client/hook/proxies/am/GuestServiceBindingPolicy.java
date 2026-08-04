package com.lody.virtual.client.hook.proxies.am;

/** Distinguishes the host keep-alive service from the guest service assigned to a stub process. */
final class GuestServiceBindingPolicy {
    private GuestServiceBindingPolicy() {
    }

    static boolean shouldBindGuestApplication(String hostPackage, String servicePackage) {
        return servicePackage != null && !servicePackage.equals(hostPackage);
    }
}
