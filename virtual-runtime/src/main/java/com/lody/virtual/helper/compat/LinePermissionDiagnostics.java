package com.lody.virtual.helper.compat;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.lody.virtual.BuildConfig;
import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.os.VUserHandle;

/** Debug-only, opt-in metadata trace; never changes permission decisions. */
public final class LinePermissionDiagnostics {
    private static final Budget BUDGET = new Budget();
    private LinePermissionDiagnostics() { }

    public static void record(String stage, String permission, int decision) {
        try {
            if (!BuildConfig.DEBUG || !isAllowedStage(stage) || !isAllowedPermission(permission)) return;
            String currentPackage = VClientImpl.get().getCurrentPackage();
            int user = VUserHandle.myUserId();
            if (!isExactScope(currentPackage, user)) return;
            String enabled = Reflect.on("android.os.SystemProperties")
                    .call("get", "debug.apptwin.line_permission", "0").get();
            if (!"1".equals(enabled) || !BUDGET.reserve(SystemClock.elapsedRealtime())) return;
            Log.i("LinePermissionTrace", "stage=" + stage + " permission="
                    + permission.substring(permission.lastIndexOf('.') + 1) + " decision=" + decision
                    + " pid=" + Process.myPid() + " user=" + user);
        } catch (Throwable ignored) {
            // Diagnostics must not affect the original result or disclose exception content.
        }
    }

    static boolean isExactScope(String packageName, int user) {
        return user == 2 && "jp.naver.line.android".equals(packageName);
    }

    static boolean isAllowedStage(String stage) {
        return "PM".equals(stage) || "AM".equals(stage) || "native".equals(stage);
    }

    static boolean isAllowedPermission(String permission) {
        return "android.permission.CAMERA".equals(permission)
                || "android.permission.RECORD_AUDIO".equals(permission)
                || "android.permission.READ_EXTERNAL_STORAGE".equals(permission)
                || "android.permission.WRITE_EXTERNAL_STORAGE".equals(permission)
                || "android.permission.READ_MEDIA_IMAGES".equals(permission)
                || "android.permission.READ_MEDIA_VIDEO".equals(permission);
    }

    /** One lifetime budget per process, unaffected by opt-in toggling. */
    static final class Budget {
        private long firstAccepted = -1;
        private int events;
        synchronized boolean reserve(long now) {
            if (events >= 96 || (firstAccepted >= 0 && (now < firstAccepted || now - firstAccepted >= 120_000))) return false;
            if (firstAccepted < 0) firstAccepted = now;
            events++;
            return true;
        }
    }
}
