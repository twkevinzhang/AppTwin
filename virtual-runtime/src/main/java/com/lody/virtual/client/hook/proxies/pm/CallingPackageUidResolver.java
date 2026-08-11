package com.lody.virtual.client.hook.proxies.pm;

import com.lody.virtual.os.VUserHandle;

/** Restores the virtual user bits stripped from Binder calling UIDs. */
final class CallingPackageUidResolver {

    private static final String TRUSTED_GMS_PACKAGE = "com.google.android.gms";

    private CallingPackageUidResolver() {
    }

    static int trustedCallerVUid(int currentVUid, int callerVUid,
                                 boolean callerIsVirtualProcess) {
        if (callerIsVirtualProcess
                && VUserHandle.getUserId(callerVUid) == VUserHandle.getUserId(currentVUid)) {
            return callerVUid;
        }
        return currentVUid;
    }

    static int restoreRequestedUid(int requestedUid, int hostUid, int callerVUid) {
        if (requestedUid == hostUid
                || requestedUid == callerVUid
                || requestedUid == VUserHandle.getAppId(callerVUid)) {
            return callerVUid;
        }
        return requestedUid;
    }

    static boolean belongsToCurrentVirtualUser(int uid, int currentVUid) {
        return uid >= 0
                && VUserHandle.getUserId(uid) == VUserHandle.getUserId(currentVUid);
    }

    static boolean mayExposeCurrentGroupCandidates(
            int requestedUid,
            int hostUid,
            int currentVUid,
            int callerVUid,
            String currentPackage,
            boolean trustedGmsInstalled) {
        return requestedUid == hostUid
                && currentVUid == callerVUid
                && trustedGmsInstalled
                && TRUSTED_GMS_PACKAGE.equals(currentPackage);
    }
}
