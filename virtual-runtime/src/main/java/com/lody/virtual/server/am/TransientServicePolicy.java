package com.lody.virtual.server.am;

import android.content.Intent;
import android.content.pm.ServiceInfo;

/** Runtime policies for guest services whose lifecycle cannot be observed by host AMS. */
final class TransientServicePolicy {

    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GMS_INTENT_OPERATION_SERVICE =
            "com.google.android.gms.chimera.GmsIntentOperationService";
    private static final String CHECKIN_ACTION =
            "com.google.android.gms.checkin.CHECKIN_START_ACTION";

    private TransientServicePolicy() {
    }

    static boolean shouldRecreate(ServiceInfo info, Intent intent) {
        return shouldRecreate(
                info == null ? null : info.packageName,
                info == null ? null : info.name,
                intent == null ? null : intent.getAction());
    }

    static boolean shouldRecreate(String packageName, String serviceName, String action) {
        return GMS_PACKAGE.equals(packageName)
                && GMS_INTENT_OPERATION_SERVICE.equals(serviceName)
                && CHECKIN_ACTION.equals(action);
    }
}
