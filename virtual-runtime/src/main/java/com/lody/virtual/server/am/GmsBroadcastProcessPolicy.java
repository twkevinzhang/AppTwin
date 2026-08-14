package com.lody.virtual.server.am;

/** Starts only the microG process needed to answer its internal GCM settings query. */
final class GmsBroadcastProcessPolicy {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String SERVICE_INFO_RECEIVER =
            "org.microg.gms.gcm.ServiceInfoReceiver";
    private static final String SERVICE_INFO_REQUEST =
            "org.microg.gms.gcm.SERVICE_INFO_REQUEST";

    private GmsBroadcastProcessPolicy() {
    }

    static boolean shouldStart(String packageName, String receiverName, String action) {
        return GMS_PACKAGE.equals(packageName)
                && SERVICE_INFO_RECEIVER.equals(receiverName)
                && SERVICE_INFO_REQUEST.equals(action);
    }
}
