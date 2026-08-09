package com.lody.virtual.server.am;

import android.app.ActivityManager;
import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the immutable process identity recorded by Android's system ActivityManager.
 *
 * <p>The raw interface must be captured before AppTwin installs its ActivityManager hook. Never
 * replace this with {@code /proc/<pid>/cmdline}: a guest can change argv[0] while retaining the
 * same Linux UID as the host.</p>
 */
public final class RawSystemProcessAuthority {
    private static final AtomicReference<IInterface> RAW_ACTIVITY_MANAGER =
            new AtomicReference<>();

    private RawSystemProcessAuthority() {
    }

    /** Called once by VirtualCore before InvocationStubManager injects any hooks. */
    public static void captureBeforeHooks() {
        if (RAW_ACTIVITY_MANAGER.get() != null) return;
        try {
            IBinder activityBinder = mirror.android.os.ServiceManager.getService.call(
                    Context.ACTIVITY_SERVICE);
            IInterface raw = activityBinder == null ? null
                    : mirror.android.app.IActivityManager.Stub.asInterface.call(activityBinder);
            if (raw != null) {
                RAW_ACTIVITY_MANAGER.compareAndSet(null, raw);
            }
        } catch (Throwable unavailable) {
            // Authority is fail-closed when the raw system interface cannot be captured.
        }
    }

    public static boolean isExactHostMainProcess(int pid, int hostUid, String hostProcessName) {
        IInterface raw = RAW_ACTIVITY_MANAGER.get();
        if (raw == null || pid <= 0 || hostUid < 0 || hostProcessName == null) return false;
        try {
            List<ActivityManager.RunningAppProcessInfo> processes =
                    mirror.android.app.IActivityManager.getRunningAppProcesses
                            .callWithException(raw);
            return containsExactProcess(processes, pid, hostUid, hostProcessName);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    static boolean containsExactProcess(
            List<ActivityManager.RunningAppProcessInfo> processes,
            int pid,
            int hostUid,
            String hostProcessName) {
        if (processes == null || pid <= 0 || hostUid < 0 || hostProcessName == null) return false;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process != null
                    && process.pid == pid
                    && process.uid == hostUid
                    && hostProcessName.equals(process.processName)) {
                return true;
            }
        }
        return false;
    }
}
