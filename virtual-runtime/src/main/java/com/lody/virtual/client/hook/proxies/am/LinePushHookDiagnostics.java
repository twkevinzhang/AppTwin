package com.lody.virtual.client.hook.proxies.am;

import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;

/**
 * Metadata-only checkpoints for the exact guest microG-to-LINE C2DM system-broadcast handoff.
 *
 * <p>This class deliberately never accepts an Intent, extras, an attestation value, a message
 * payload, or an invocation result. A scope can only be created for the fixed LINE C2DM route,
 * keeping every emitted field bounded to routing metadata.</p>
 */
final class LinePushHookDiagnostics {
    private static final String TAG = "LinePushDelivery";
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    private static final String BROADCAST_INTENT = "broadcastIntent";
    private static final String BROADCAST_INTENT_WITH_FEATURE = "broadcastIntentWithFeature";
    private static final int MAX_EVENTS_PER_WINDOW = 128;
    private static final long EVENT_WINDOW_MILLIS = 60_000L;

    private static long windowStartedAt;
    private static int eventsInWindow;

    private LinePushHookDiagnostics() {
    }

    static Scope exactScope(String senderPackage, int senderVuid, int virtualUserId,
            String action, String targetPackage, boolean noComponent, String methodName) {
        if (!isExactLineC2dm(senderPackage, senderVuid, virtualUserId, action,
                targetPackage, noComponent) || !isSupportedMethod(methodName)) {
            return null;
        }
        return new Scope(virtualUserId,
                BROADCAST_INTENT_WITH_FEATURE.equals(methodName) ? "with-feature" : "base");
    }

    static boolean isExactLineC2dm(String senderPackage, int senderVuid, int virtualUserId,
            String action, String targetPackage, boolean noComponent) {
        return GMS_PACKAGE.equals(senderPackage)
                && senderVuid >= 0
                && virtualUserId > 0
                && VUserHandle.getUserId(senderVuid) == virtualUserId
                && C2DM_RECEIVE.equals(action)
                && LINE_PACKAGE.equals(targetPackage)
                && noComponent;
    }

    static void hookEntry(Scope scope) {
        event(scope, "hook-entry");
    }

    static void attestation(Scope scope, boolean issued) {
        event(scope, issued ? "attestation-issued" : "attestation-rejected");
    }

    static void wrapperCreated(Scope scope, boolean created) {
        event(scope, created ? "redirected-wrapper-created" : "redirected-wrapper-rejected");
    }

    static void systemInvokeResult(Scope scope) {
        event(scope, "system-broadcast-invoke-result");
    }

    static void systemInvokeException(Scope scope) {
        event(scope, "system-broadcast-invoke-exception");
    }

    private static boolean isSupportedMethod(String methodName) {
        return BROADCAST_INTENT.equals(methodName)
                || BROADCAST_INTENT_WITH_FEATURE.equals(methodName);
    }

    private static void event(Scope scope, String stage) {
        if (scope == null || !takeEventPermit(System.currentTimeMillis())) return;
        VLog.i(TAG, "stage=%s user=%d sender=gms target=line action=c2dm method=%s",
                stage, scope.virtualUserId, scope.methodKind);
    }

    static synchronized boolean takeEventPermit(long now) {
        if (windowStartedAt == 0L || now < windowStartedAt
                || now - windowStartedAt >= EVENT_WINDOW_MILLIS) {
            windowStartedAt = now;
            eventsInWindow = 0;
        }
        if (eventsInWindow >= MAX_EVENTS_PER_WINDOW) return false;
        eventsInWindow++;
        return true;
    }

    static synchronized void resetRateLimitForTest() {
        windowStartedAt = 0L;
        eventsInWindow = 0;
    }

    static final class Scope {
        final int virtualUserId;
        final String methodKind;

        private Scope(int virtualUserId, String methodKind) {
            this.virtualUserId = virtualUserId;
            this.methodKind = methodKind;
        }
    }
}
