package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinePushClosedGateRecoveryPolicyTest {
    @Test
    public void acceptsOnlySignatureProtectedExactLineC2dmForDesiredCloneUser() {
        assertTrue(eligible(true, LINE, LINE, LINE, LINE,
                false, false, C2DM, 1, true));
        assertFalse(LinePushClosedGateRecoveryPolicy.isEligible(false, true,
                LINE, LINE, LINE, LINE, false, false, C2DM,
                GMS, 100_001, 1, 100_001, 1, true));

        assertFalse(eligible(false, LINE, LINE, LINE, LINE,
                false, false, C2DM, 1, true));
        assertFalse(eligible(true, null, LINE, LINE, LINE,
                false, false, C2DM, 1, true));
        assertFalse(eligible(true, LINE, LINE, LINE, null,
                false, false, C2DM, 1, true));
        assertFalse(eligible(true, LINE, LINE, LINE, "com.discord",
                false, false, C2DM, 1, true));
        assertFalse(eligible(true, "com.discord", LINE, LINE, LINE,
                false, false, C2DM, 1, true));
        assertFalse(eligible(true, LINE, "com.discord", LINE, LINE,
                false, false, C2DM, 1, true));
        assertFalse(eligible(true, LINE, LINE, LINE + ":secondary", LINE,
                false, false, C2DM, 1, true));
        assertFalse(eligible(true, LINE, LINE, LINE, LINE,
                true, false, C2DM, 1, true));
        assertFalse(eligible(true, LINE, LINE, LINE, LINE,
                false, true, C2DM, 1, true));
        assertFalse(eligible(true, LINE, LINE, LINE, LINE,
                false, false, "android.intent.action.SCREEN_ON", 1, true));
        assertFalse(eligible(true, LINE, LINE, LINE, LINE,
                false, false, C2DM, 0, true));
        assertFalse(eligible(true, LINE, LINE, LINE, LINE,
                false, false, C2DM, -1, true));
        assertFalse(eligible(true, LINE, LINE, LINE, LINE,
                false, false, C2DM, 1, false));
    }

    @Test
    public void acceptsOnlyPinnedGmsSenderFromTheSameVirtualUserAndVuid() {
        assertTrue(eligibleWithSender(GMS, 100_001, 1, 100_001));
        assertFalse(eligibleWithSender(null, 100_001, 1, 100_001));
        assertFalse(eligibleWithSender(LINE, 100_001, 1, 100_001));
        assertFalse(eligibleWithSender("com.discord", 100_001, 1, 100_001));
        assertFalse(eligibleWithSender(GMS, 100_001, 2, 100_001));
        assertFalse(eligibleWithSender(GMS, 100_002, 1, 100_001));
        assertFalse(eligibleWithSender(GMS, -1, 1, 100_001));
        assertFalse(eligibleWithSender(GMS, 100_001, 1, -1));
    }

    @Test
    public void forgedOriginalSenderExtraCannotReplaceOuterBoundClientIdentity() {
        // The embedded Intent may claim GMS in its extras, but only outer metadata written by the
        // bound MethodProxy reaches this policy. A LINE/Discord/null outer sender stays rejected.
        assertFalse(eligibleWithSender(LINE, 100_001, 1, 100_001));
        assertFalse(eligibleWithSender("com.discord", 100_001, 1, 100_001));
        assertFalse(eligibleWithSender(null, 100_001, 1, 100_001));
    }

    private static boolean eligible(boolean signatureProtectedWrapper,
            String targetPackage, String receiverPackage, String receiverProcess,
            String originalPackage, boolean wrapperHasComponent,
            boolean originalHasComponent, String originalAction,
            int userId, boolean desiredUser) {
        return LinePushClosedGateRecoveryPolicy.isEligible(true, signatureProtectedWrapper,
                targetPackage, receiverPackage, receiverProcess, originalPackage,
                wrapperHasComponent, originalHasComponent, originalAction,
                GMS, 100_001, userId, 100_001, userId, desiredUser);
    }

    private static boolean eligibleWithSender(String senderPackage, int senderVuid,
            int senderUserId, int expectedSenderVuid) {
        return LinePushClosedGateRecoveryPolicy.isEligible(true, true,
                LINE, LINE, LINE, LINE, false, false, C2DM,
                senderPackage, senderVuid, senderUserId, expectedSenderVuid, 1, true);
    }

    private static final String LINE = "jp.naver.line.android";
    private static final String GMS = "com.google.android.gms";
    private static final String C2DM = "com.google.android.c2dm.intent.RECEIVE";
}
