package com.lody.virtual.server.am;

import android.app.ActivityManager;
import android.os.Build;

/** API-aware task snapshot bridge. Identity does not itself imply a live task owner. */
final class PhysicalTaskSnapshot {
    private PhysicalTaskSnapshot() {}

    // https://developer.android.com/reference/android/app/ActivityManager.RecentTaskInfo
    // API 29 replaces deprecated id/persistentId with TaskInfo.taskId and isRunning.
    static int canonicalTaskId(ActivityManager.RecentTaskInfo info) {
        if (info == null) return -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return PhysicalTaskIdentity.canonicalTaskId(Build.VERSION.SDK_INT, -1, info.taskId);
        }
        // noinspection deprecation
        return PhysicalTaskIdentity.canonicalTaskId(Build.VERSION.SDK_INT, info.persistentId, -1);
    }

    static int runningTaskId(ActivityManager.RecentTaskInfo info) {
        if (info == null) return -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return PhysicalTaskIdentity.runningTaskId(Build.VERSION.SDK_INT, -1, -1,
                    info.taskId, info.isRunning, info.numActivities);
        }
        // noinspection deprecation
        return PhysicalTaskIdentity.runningTaskId(Build.VERSION.SDK_INT, info.id,
                info.persistentId, -1, false, 0);
    }
}
