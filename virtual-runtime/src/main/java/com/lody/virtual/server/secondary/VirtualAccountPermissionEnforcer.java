package com.lody.virtual.server.secondary;

import android.Manifest;
import android.content.AttributionSource;
import android.os.PermissionEnforcer;

/** Grants only the virtual AccountManager process access to a guest authenticator transport. */
final class VirtualAccountPermissionEnforcer extends PermissionEnforcer {
    private static final int PERMISSION_GRANTED = 0;
    private static final int PERMISSION_HARD_DENIED = 2;
    private final int accountManagerPid;

    VirtualAccountPermissionEnforcer(int accountManagerPid) {
        this.accountManagerPid = accountManagerPid;
    }

    @Override
    protected int checkPermission(String permission, int pid, int uid) {
        return permissionResult(permission, accountManagerPid, accountManagerPid);
    }

    @Override
    protected int checkPermission(String permission, AttributionSource source) {
        return permissionResult(permission, accountManagerPid, accountManagerPid);
    }

    static int permissionResult(String permission, int accountManagerPid, int callingPid) {
        return Manifest.permission.ACCOUNT_MANAGER.equals(permission)
                && accountManagerPid > 0
                && accountManagerPid == callingPid
                ? PERMISSION_GRANTED
                : PERMISSION_HARD_DENIED;
    }
}
