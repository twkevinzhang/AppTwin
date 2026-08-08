package com.lody.virtual.client.hook.proxies.am;

/** Compatibility policy for optional Google Play services that cannot run in the guest. */
final class GmsServiceBindingPolicy {
    static final String GMS_PACKAGE = "com.google.android.gms";
    static final String WEARABLE_BIND_ACTION = "com.google.android.gms.wearable.BIND";

    private GmsServiceBindingPolicy() {
    }

    static boolean shouldRejectUnavailableWearableBinding(
            String callerPackage,
            String action,
            String servicePackage) {
        return !GMS_PACKAGE.equals(callerPackage)
                && GMS_PACKAGE.equals(servicePackage)
                && WEARABLE_BIND_ACTION.equals(action);
    }
}
