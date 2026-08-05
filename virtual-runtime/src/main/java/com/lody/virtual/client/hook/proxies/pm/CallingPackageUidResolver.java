package com.lody.virtual.client.hook.proxies.pm;

import com.lody.virtual.os.VUserHandle;

/** Restores the virtual user bits stripped from Binder calling UIDs. */
final class CallingPackageUidResolver {

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
}
