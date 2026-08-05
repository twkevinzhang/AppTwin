package com.lody.virtual.server.am;

import com.lody.virtual.os.VUserHandle;

/** Maps a virtual isolated process to a stable UID in Android's isolated-app range. */
final class IsolatedProcessUidPolicy {

    static final int NO_OVERRIDE = -1;

    private static final int ISOLATED_UID_COUNT =
            VUserHandle.LAST_ISOLATED_UID - VUserHandle.FIRST_ISOLATED_UID + 1;

    private IsolatedProcessUidPolicy() {
    }

    static int reportedUidOverride(int vuid, boolean isolatedProcess) {
        if (!isolatedProcess) {
            return VUserHandle.getAppId(vuid);
        }
        int userId = VUserHandle.getUserId(vuid);
        int appId = VUserHandle.getAppId(vuid);
        int stableOffset = Math.floorMod(31 * userId + appId, ISOLATED_UID_COUNT);
        return VUserHandle.FIRST_ISOLATED_UID + stableOffset;
    }
}
