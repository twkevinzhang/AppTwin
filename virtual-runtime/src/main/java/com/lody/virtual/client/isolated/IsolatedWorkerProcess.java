package com.lody.virtual.client.isolated;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.util.List;

/** Process-name contract shared by the host Application and isolated worker stubs. */
public final class IsolatedWorkerProcess {
    public static final String PROCESS_MARKER = ":va_isolated_";

    private IsolatedWorkerProcess() {
    }

    public static boolean isCurrentProcess(Context context) {
        return isWorkerProcessName(context.getPackageName(), currentProcessName(context));
    }

    public static boolean isWorkerProcessName(String packageName, String processName) {
        return packageName != null && processName != null
                && processName.startsWith(packageName + PROCESS_MARKER);
    }

    private static String currentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) {
            return null;
        }
        int pid = Process.myPid();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == pid) {
                return process.processName;
            }
        }
        return null;
    }
}
