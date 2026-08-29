package com.lody.virtual.client.hook.proxies.am;

/** Compatibility policy for an optional GMS Wearable service unavailable to guest apps. */
final class GmsServiceBindingPolicy {
    static final String GMS_PACKAGE = "com.google.android.gms";
    static final String WEARABLE_BIND_ACTION = "com.google.android.gms.wearable.BIND";

    private GmsServiceBindingPolicy() {
    }

    static String selectServicePackage(
            String requestedPackage,
            String requestedComponentPackage,
            String resolvedPackage) {
        if (requestedPackage != null) {
            return requestedPackage;
        }
        if (requestedComponentPackage != null) {
            return requestedComponentPackage;
        }
        return resolvedPackage;
    }

    static boolean shouldRejectUnavailableWearableBinding(
            boolean serverOwnedCall,
            String callerPackage,
            String action,
            String servicePackage) {
        return !serverOwnedCall && shouldRejectUnavailableWearableBinding(
                callerPackage, action, servicePackage);
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
