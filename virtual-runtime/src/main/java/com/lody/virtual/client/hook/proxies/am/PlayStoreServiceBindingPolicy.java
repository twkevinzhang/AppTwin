package com.lody.virtual.client.hook.proxies.am;

/** Compatibility policy for Play services whose Binder contract requires a local Java object. */
final class PlayStoreServiceBindingPolicy {
    static final String PLAY_STORE_PACKAGE = "com.android.vending";
    static final String FIREBASE_MESSAGING_SERVICE =
            "com.google.android.finsky.dfenotification.impl.PhoneskyFirebaseMessagingService";

    private PlayStoreServiceBindingPolicy() {
    }

    static boolean shouldRejectLocalOnlyBinding(
            String callerPackage,
            String servicePackage,
            String serviceClassName) {
        return PLAY_STORE_PACKAGE.equals(callerPackage)
                && PLAY_STORE_PACKAGE.equals(servicePackage)
                && FIREBASE_MESSAGING_SERVICE.equals(serviceClassName);
    }
}
