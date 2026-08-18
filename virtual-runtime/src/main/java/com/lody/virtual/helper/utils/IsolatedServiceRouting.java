package com.lody.virtual.helper.utils;

import android.content.Intent;

/** Internal transport for the framework {@code bindIsolatedService} instance identity. */
public final class IsolatedServiceRouting {
    private static final String EXTRA_INSTANCE_NAME = "_VA_|_isolated_service_instance_";

    private IsolatedServiceRouting() {
    }

    /** Treats the absent and empty instance names as the ordinary, unnamed service instance. */
    public static String normalizeInstanceName(String instanceName) {
        return instanceName == null || instanceName.length() == 0 ? null : instanceName;
    }

    public static void putInstanceName(Intent intent, String instanceName) {
        String normalized = normalizeInstanceName(instanceName);
        if (normalized == null) {
            intent.removeExtra(EXTRA_INSTANCE_NAME);
        } else {
            intent.putExtra(EXTRA_INSTANCE_NAME, normalized);
        }
    }

    /** Reads and removes the private routing value before the intent reaches guest code. */
    public static String takeInstanceName(Intent intent) {
        String instanceName = normalizeInstanceName(intent.getStringExtra(EXTRA_INSTANCE_NAME));
        intent.removeExtra(EXTRA_INSTANCE_NAME);
        return instanceName;
    }
}
