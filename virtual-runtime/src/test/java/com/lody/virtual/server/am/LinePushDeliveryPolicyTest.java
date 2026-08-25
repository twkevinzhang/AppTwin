package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinePushDeliveryPolicyTest {
    @Test
    public void directBaselineNeverRecoversAClosedGate() {
        assertFalse(LinePushDeliveryPolicy.shouldRecoverClosedGate(
                LinePushDeliveryMode.DIRECT_BASELINE, true, true, true));
    }

    @Test
    public void reliableModeRequiresExactScopeAutomaticRecoveryAndCurrentStopPermit() {
        assertTrue(LinePushDeliveryPolicy.shouldRecoverClosedGate(
                LinePushDeliveryMode.RELIABLE_GATED, true, true, true));
        assertFalse(LinePushDeliveryPolicy.shouldRecoverClosedGate(
                LinePushDeliveryMode.RELIABLE_GATED, false, true, true));
        assertFalse(LinePushDeliveryPolicy.shouldRecoverClosedGate(
                LinePushDeliveryMode.RELIABLE_GATED, true, false, true));
        assertFalse(LinePushDeliveryPolicy.shouldRecoverClosedGate(
                LinePushDeliveryMode.RELIABLE_GATED, true, true, false));
        assertFalse(LinePushDeliveryPolicy.shouldRecoverClosedGate(
                null, true, true, true));
    }

    @Test
    public void reliableModeStillRejectsWrongPackageActionUserAndSender() {
        assertTrue(recoverReliable(LINE, LINE, LINE, LINE, C2DM,
                GMS, 100_001, 1, 100_001, 1, true, true));
        assertFalse(recoverReliable("com.discord", LINE, LINE, LINE, C2DM,
                GMS, 100_001, 1, 100_001, 1, true, true));
        assertFalse(recoverReliable(LINE, "com.discord", LINE, LINE, C2DM,
                GMS, 100_001, 1, 100_001, 1, true, true));
        assertFalse(recoverReliable(LINE, LINE, LINE, LINE,
                "android.intent.action.SCREEN_ON",
                GMS, 100_001, 1, 100_001, 1, true, true));
        assertFalse(recoverReliable(LINE, LINE, LINE, LINE, C2DM,
                LINE, 100_001, 1, 100_001, 1, true, true));
        assertFalse(recoverReliable(LINE, LINE, LINE, LINE, C2DM,
                GMS, 100_001, 2, 100_001, 1, true, true));
        assertFalse(recoverReliable(LINE, LINE, LINE, LINE, C2DM,
                GMS, 100_002, 1, 100_001, 1, true, true));
        assertFalse(recoverReliable(LINE, LINE, LINE, LINE, C2DM,
                GMS, 100_001, 1, 100_001, 1, false, true));
        assertFalse(recoverReliable(LINE, LINE, LINE, LINE, C2DM,
                GMS, 100_001, 1, 100_001, 1, true, false));
    }

    private static boolean recoverReliable(String targetPackage, String receiverPackage,
            String receiverProcess, String originalPackage, String originalAction,
            String senderPackage, int senderVuid, int senderUserId, int expectedSenderVuid,
            int userId, boolean automaticRecoveryAllowed, boolean stopPermitCurrent) {
        boolean exactRouteEligible = LinePushClosedGateRecoveryPolicy.isEligible(
                true, true, targetPackage, receiverPackage, receiverProcess,
                originalPackage, false, false, originalAction,
                senderPackage, senderVuid, senderUserId, expectedSenderVuid,
                userId, true);
        return LinePushDeliveryPolicy.shouldRecoverClosedGate(
                LinePushDeliveryMode.RELIABLE_GATED,
                exactRouteEligible, automaticRecoveryAllowed, stopPermitCurrent);
    }

    private static final String LINE = "jp.naver.line.android";
    private static final String GMS = "com.google.android.gms";
    private static final String C2DM = "com.google.android.c2dm.intent.RECEIVE";
}
