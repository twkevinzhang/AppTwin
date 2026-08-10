package com.lody.virtual.server.am;

import com.lody.virtual.os.VUserHandle;

/** Selects the native UID override used by a guest process. */
final class IsolatedProcessUidPolicy {

    static final int NO_OVERRIDE = -1;

    private static final int ISOLATED_UID_COUNT =
            VUserHandle.LAST_ISOLATED_UID - VUserHandle.FIRST_ISOLATED_UID + 1;

    private IsolatedProcessUidPolicy() {
    }

    static int reportedUidOverride(int vuid, boolean isolatedProcess, int hostProcessUid) {
        if (!isolatedProcess) {
            // Regular guests already execute with the host process's real kernel UID. Do not
            // activate the native override: Binder/Looper must continue seeing the kernel value.
            return NO_OVERRIDE;
        }
        int userId = VUserHandle.getUserId(vuid);
        int appId = VUserHandle.getAppId(vuid);
        int stableOffset = Math.floorMod(31 * userId + appId, ISOLATED_UID_COUNT);
        return VUserHandle.FIRST_ISOLATED_UID + stableOffset;
    }
}
