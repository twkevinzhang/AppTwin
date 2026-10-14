package com.lody.virtual.server.am;

import android.app.ActivityManager;
import android.os.Process;
import com.lody.virtual.client.ipc.VActivityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test-only bridge to immutable pre-hook authority, without adding a production API. */
public final class ColdPolicyProcessGuard {
    private ColdPolicyProcessGuard() { }

    public static Set<Integer> verifyRawAuthority() {
        int mainPid = Process.myPid();
        int hostUid = Process.myUid();
        final int enginePid;
        try {
            // The legacy getSystemPid RPC returns a UID. This existing scoped RPC returns
            // the actual server PID, which is still cross-checked against immutable raw AM.
            enginePid = VActivityManager.get().getScopedSyncManagerPid();
        } catch (android.os.RemoteException unavailable) {
            throw new IllegalStateException("Actual engine PID unavailable; mutation refused", unavailable);
        }
        if (mainPid <= 0 || enginePid <= 0 || enginePid == mainPid) {
            throw new IllegalStateException("Invalid main/engine identity; mutation refused");
        }
        List<ActivityManager.RunningAppProcessInfo> snapshot = RawSystemProcessAuthority.rawProcessSnapshot();
        if (snapshot == null) throw new IllegalStateException("Raw system authority unavailable; mutation refused");
        boolean mainFound = false;
        boolean engineFound = false;
        int mainCount = 0;
        int engineCount = 0;
        int unknownCount = 0;
        StringBuilder relatedIdentities = new StringBuilder();
        for (ActivityManager.RunningAppProcessInfo process : snapshot) {
            boolean main = process.pid == mainPid && process.uid == hostUid && "org.apptwin".equals(process.processName);
            boolean engine = process.pid == enginePid && process.uid == hostUid && "org.apptwin:x".equals(process.processName);
            mainFound |= main;
            engineFound |= engine;
            boolean hostPrefix = process.processName != null &&
                    (process.processName.equals("org.apptwin") || process.processName.startsWith("org.apptwin:"));
            if (process.uid == hostUid || hostPrefix) {
                if (main) mainCount++;
                else if (engine) engineCount++;
                else unknownCount++;
                if (relatedIdentities.length() > 0) relatedIdentities.append(';');
                String role = main ? "MAIN" : engine ? "ENGINE" : "UNKNOWN";
                // Only immutable host-package names are exposed; unrelated names stay redacted.
                relatedIdentities.append("pid=").append(process.pid).append(",uid=").append(process.uid)
                        .append(",role=").append(role).append(",name=")
                        .append(hostPrefix ? process.processName : "OTHER_HOST_UID");
            }
        }
        if (unknownCount > 0 || !mainFound || !engineFound) {
            throw new IllegalStateException("Immutable cold guard refused: expectedMainPid=" + mainPid +
                    ",expectedEnginePid=" + enginePid + ",hostUid=" + hostUid + ",mainCount=" + mainCount +
                    ",engineCount=" + engineCount + ",unknownCount=" + unknownCount + ",related=[" + relatedIdentities + "]");
        }
        Set<Integer> allowed = new HashSet<>();
        allowed.add(mainPid);
        allowed.add(enginePid);
        return allowed;
    }
}
