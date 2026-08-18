package com.lody.virtual.server.am;

import android.content.pm.ServiceInfo;

/** Selects guest bindings whose one-shot process allocation must not fail under contention. */
final class GuestServiceStartPolicy {
    private static final String FIREFOX_PACKAGE = "org.mozilla.firefox";
    private static final String GECKO_CHILD_SERVICE_PREFIX =
            "org.mozilla.gecko.process.GeckoChildProcessServices$";

    private GuestServiceStartPolicy() {
    }

    static boolean shouldSerializeBinding(ServiceInfo serviceInfo) {
        return serviceInfo != null
                && FIREFOX_PACKAGE.equals(serviceInfo.packageName)
                && serviceInfo.name != null
                && serviceInfo.name.startsWith(GECKO_CHILD_SERVICE_PREFIX);
    }
}
