package com.lody.virtual.server.am;

import android.content.Intent;
import android.os.IBinder;

import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.PendingResultData;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded delivery checkpoints for cloned LINE pushes.
 *
 * <p>Only routing metadata is recorded. Message contents, Intent extras, registration IDs and
 * Binder tokens must never be passed to or emitted by this class.</p>
 */
final class LinePushDeliveryDiagnostics {
    private static final String TAG = "LinePushDelivery";
    private static final int MAX_ACTIVE_TRACES = 64;
    private static final int MAX_EVENTS_PER_WINDOW = 256;
    private static final long EVENT_WINDOW_MILLIS = 60_000L;

    private static final Map<IBinder, Trace> TRACES = new LinkedHashMap<>();
    private static long nextTraceId = 1L;
    private static long windowStartedAt;
    private static int eventsInWindow;

    private LinePushDeliveryDiagnostics() {
    }

    static boolean isLineC2dmWrapper(Intent wrapper, String receiverPackage) {
        if (!LinePushBroadcastPolicy.LINE_PACKAGE.equals(receiverPackage) || wrapper == null) {
            return false;
        }
        Intent original = wrapper.getParcelableExtra("_VA_|_intent_");
        return original != null
                && LinePushBroadcastPolicy.C2DM_RECEIVE.equals(original.getAction());
    }

    static void wrapperScope(int userId, String targetPackage, String receiverPackage,
                             boolean accepted) {
        log("wrapper scope=" + (accepted ? "allow" : "reject")
                + " user=" + userId
                + " target=" + packageScope(targetPackage)
                + " receiver=" + packageScope(receiverPackage));
    }

    static void begin(PendingResultData result, int userId, String targetPackage,
                      String receiverPackage) {
        if (result == null || result.mToken == null) {
            return;
        }
        Trace trace;
        synchronized (LinePushDeliveryDiagnostics.class) {
            trimTracesLocked();
            trace = new Trace(nextTraceId++, userId, targetPackage, receiverPackage);
            TRACES.put(result.mToken, trace);
        }
        log(trace, "entry");
    }

    static void checkpoint(PendingResultData result, String stage) {
        checkpoint(result == null ? null : result.mToken, stage);
    }

    static void checkpoint(IBinder token, String stage) {
        Trace trace;
        synchronized (LinePushDeliveryDiagnostics.class) {
            trace = token == null ? null : TRACES.get(token);
        }
        if (trace != null) {
            log(trace, stage);
        }
    }

    static void lease(PendingResultData result, String stage, ProcessRecord process,
                      int pendingCount) {
        Trace trace;
        synchronized (LinePushDeliveryDiagnostics.class) {
            trace = result == null || result.mToken == null
                    ? null : TRACES.get(result.mToken);
        }
        if (trace != null && process != null) {
            log(trace, stage
                    + " slot=" + process.vpid
                    + " generation=" + process.generation
                    + " pending=" + Math.max(0, pendingCount));
        }
    }

    static void finish(IBinder token, String reason) {
        Trace trace;
        synchronized (LinePushDeliveryDiagnostics.class) {
            trace = token == null ? null : TRACES.remove(token);
        }
        if (trace != null) {
            log(trace, "finish reason=" + reason);
        }
    }

    private static void log(Trace trace, String stage) {
        log("trace=" + trace.id
                + " stage=" + stage
                + " user=" + trace.userId
                + " target=" + packageScope(trace.targetPackage)
                + " receiver=" + packageScope(trace.receiverPackage));
    }

    private static void log(String event) {
        if (!takeEventPermit(System.currentTimeMillis())) {
            return;
        }
        VLog.i(TAG, event);
    }

    static synchronized boolean takeEventPermit(long now) {
        if (windowStartedAt == 0L || now < windowStartedAt
                || now - windowStartedAt >= EVENT_WINDOW_MILLIS) {
            windowStartedAt = now;
            eventsInWindow = 0;
        }
        if (eventsInWindow >= MAX_EVENTS_PER_WINDOW) {
            return false;
        }
        eventsInWindow++;
        return true;
    }

    static synchronized void resetRateLimitForTest() {
        windowStartedAt = 0L;
        eventsInWindow = 0;
    }

    private static void trimTracesLocked() {
        while (TRACES.size() >= MAX_ACTIVE_TRACES) {
            Iterator<IBinder> iterator = TRACES.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static String packageScope(String packageName) {
        if (packageName == null) {
            return "implicit";
        }
        return LinePushBroadcastPolicy.LINE_PACKAGE.equals(packageName) ? "line" : "other";
    }

    private static final class Trace {
        final long id;
        final int userId;
        final String targetPackage;
        final String receiverPackage;

        Trace(long id, int userId, String targetPackage, String receiverPackage) {
            this.id = id;
            this.userId = userId;
            this.targetPackage = targetPackage;
            this.receiverPackage = receiverPackage;
        }
    }
}
