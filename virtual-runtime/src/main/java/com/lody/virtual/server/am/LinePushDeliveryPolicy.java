package com.lody.virtual.server.am;

/** Final fail-closed admission policy for cloned-LINE closed-gate recovery. */
final class LinePushDeliveryPolicy {
    private LinePushDeliveryPolicy() {
    }

    static boolean shouldRecoverClosedGate(LinePushDeliveryMode mode,
            boolean exactRouteEligible, boolean automaticRecoveryAllowed,
            boolean stopPermitCurrent) {
        return mode == LinePushDeliveryMode.RELIABLE_GATED
                && exactRouteEligible
                && automaticRecoveryAllowed
                && stopPermitCurrent;
    }
}
