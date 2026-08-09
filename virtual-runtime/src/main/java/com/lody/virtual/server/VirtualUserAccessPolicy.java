package com.lody.virtual.server;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.server.pm.VPackageManagerService;

import java.util.Arrays;

/** Shared server-side authority boundary for APIs that accept a virtual user id. */
public final class VirtualUserAccessPolicy {
    private VirtualUserAccessPolicy() {
    }

    public static void enforceCallerUserOrHost(int requestedUserId) {
        int callingVuid = VBinder.getCallingUid();
        if (callingVuid == VirtualCore.get().myUid()) return;
        if (callingVuid < 0 || requestedUserId < 0
                || VUserHandle.getUserId(callingVuid) != requestedUserId) {
            throw new SecurityException("Cross-user virtual operation denied");
        }
    }

    public static void enforceHost() {
        if (!isHostCaller()) {
            throw new SecurityException("Host authority required");
        }
    }

    public static boolean isHostCaller() {
        return VBinder.getCallingUid() == VirtualCore.get().myUid();
    }

    public static void enforceCallerPackageOrHost(String packageName, int requestedUserId) {
        enforceCallerUserOrHost(requestedUserId);
        int callingVuid = VBinder.getCallingUid();
        if (callingVuid == VirtualCore.get().myUid()) return;
        String[] packages = VPackageManagerService.get().getPackagesForUid(callingVuid);
        if (packageName == null || packages == null
                || !Arrays.asList(packages).contains(packageName)) {
            throw new SecurityException("Virtual package ownership denied");
        }
    }

    static boolean isCallerUserOrHost(int callingVuid, int hostUid, int requestedUserId) {
        return callingVuid == hostUid
                || (requestedUserId >= 0 && VUserHandle.getUserId(callingVuid) == requestedUserId);
    }
}
