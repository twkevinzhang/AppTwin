package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class LinePushHookDiagnosticsTest {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    private static final int USER_ONE_GMS_VUID = 110002;

    @After
    public void resetRateLimit() {
        LinePushHookDiagnostics.resetRateLimitForTest();
    }

    @Test
    public void exactVirtualGmsLineC2dmScopeIsAccepted() {
        assertNotNull(LinePushHookDiagnostics.exactScope(
                GMS_PACKAGE, USER_ONE_GMS_VUID, 1, C2DM_RECEIVE, LINE_PACKAGE,
                true, "broadcastIntent"));
        assertNotNull(LinePushHookDiagnostics.exactScope(
                GMS_PACKAGE, USER_ONE_GMS_VUID, 1, C2DM_RECEIVE, LINE_PACKAGE,
                true, "broadcastIntentWithFeature"));
    }

    @Test
    public void everyWrongScopeIsRejected() {
        assertRejected("other.sender", USER_ONE_GMS_VUID, 1,
                C2DM_RECEIVE, LINE_PACKAGE, true, "broadcastIntent");
        assertRejected(GMS_PACKAGE, -1, 1,
                C2DM_RECEIVE, LINE_PACKAGE, true, "broadcastIntent");
        assertRejected(GMS_PACKAGE, 10002, 0,
                C2DM_RECEIVE, LINE_PACKAGE, true, "broadcastIntent");
        assertRejected(GMS_PACKAGE, USER_ONE_GMS_VUID, 2,
                C2DM_RECEIVE, LINE_PACKAGE, true, "broadcastIntent");
        assertRejected(GMS_PACKAGE, USER_ONE_GMS_VUID, 1,
                "other.action", LINE_PACKAGE, true, "broadcastIntent");
        assertRejected(GMS_PACKAGE, USER_ONE_GMS_VUID, 1,
                C2DM_RECEIVE, "other.target", true, "broadcastIntent");
        assertRejected(GMS_PACKAGE, USER_ONE_GMS_VUID, 1,
                C2DM_RECEIVE, LINE_PACKAGE, false, "broadcastIntent");
        assertRejected(GMS_PACKAGE, USER_ONE_GMS_VUID, 1,
                C2DM_RECEIVE, LINE_PACKAGE, true, "otherMethod");
    }

    @Test
    public void eventRateLimitIsBoundedAndResetsByWindow() {
        LinePushHookDiagnostics.resetRateLimitForTest();
        for (int index = 0; index < 128; index++) {
            assertTrue(LinePushHookDiagnostics.takeEventPermit(1_000L + index));
        }
        assertFalse(LinePushHookDiagnostics.takeEventPermit(2_000L));
        assertTrue(LinePushHookDiagnostics.takeEventPermit(61_000L));
    }

    private static void assertRejected(String senderPackage, int senderVuid,
            int virtualUserId, String action, String targetPackage, boolean noComponent,
            String methodName) {
        assertNull(LinePushHookDiagnostics.exactScope(
                senderPackage, senderVuid, virtualUserId, action, targetPackage,
                noComponent, methodName));
    }
}
