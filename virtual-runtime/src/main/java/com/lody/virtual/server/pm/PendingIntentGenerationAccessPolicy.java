package com.lody.virtual.server.pm;

import com.lody.virtual.os.VUserHandle;

import java.util.Arrays;

final class PendingIntentGenerationAccessPolicy {
    private PendingIntentGenerationAccessPolicy() {
    }

    static boolean canRead(
            int callingVuid, int hostUid, String packageName, int requestedUserId,
            String[] callerPackages) {
        if (callingVuid == hostUid) return true;
        return VUserHandle.getUserId(callingVuid) == requestedUserId
                && callerPackages != null
                && Arrays.asList(callerPackages).contains(packageName);
    }
}
