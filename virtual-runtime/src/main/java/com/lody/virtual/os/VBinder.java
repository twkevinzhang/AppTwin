package com.lody.virtual.os;

import android.os.Binder;
import android.os.Process;

import com.lody.virtual.client.ipc.VActivityManager;

/**
 * @author Lody
 */

public class VBinder {

    public static int getCallingUid() {
        int callingPid = Binder.getCallingPid();
        // Bootstrap and direct in-process service calls must not recurse through an ActivityManager
        // binder that has not been published yet.
        if (callingPid == Process.myPid()) return Process.myUid();
        return VActivityManager.get().getUidByPid(callingPid);
    }

    public static int getBaseCallingUid() {
        return VUserHandle.getAppId(getCallingUid());
    }

    public static int getCallingPid() {
        return Binder.getCallingPid();
    }

    public static VUserHandle getCallingUserHandle() {
        return new VUserHandle(VUserHandle.getUserId(getCallingUid()));
    }
}
