package com.lody.virtual.server.am;

/** Fail-closed eligibility policy for restarting the daemon after a cloned LINE push. */
final class LinePushClosedGateRecoveryPolicy {
    private LinePushClosedGateRecoveryPolicy() {
    }

    static boolean isEligible(boolean senderAttested, boolean signatureProtectedWrapper,
            String targetPackage, String receiverPackage, String receiverProcess,
            String originalPackage, boolean wrapperHasComponent,
            boolean originalHasComponent, String originalAction,
            String virtualSenderPackage, int virtualSenderVuid,
            int virtualSenderUserId, int expectedSenderVuid,
            int userId, boolean desiredUser) {
        return senderAttested
                && signatureProtectedWrapper
                && userId > 0
                && desiredUser
                && TrustedGmsCloudMessagingSupervisor.GMS_PACKAGE.equals(virtualSenderPackage)
                && virtualSenderUserId == userId
                && virtualSenderVuid >= 0
                && virtualSenderVuid == expectedSenderVuid
                && LinePushBroadcastPolicy.LINE_PACKAGE.equals(targetPackage)
                && LinePushBroadcastPolicy.LINE_PACKAGE.equals(originalPackage)
                && LinePushBroadcastPolicy.LINE_PACKAGE.equals(receiverPackage)
                && LinePushBroadcastPolicy.LINE_PACKAGE.equals(receiverProcess)
                && !wrapperHasComponent
                && !originalHasComponent
                && LinePushBroadcastPolicy.C2DM_RECEIVE.equals(originalAction);
    }
}
