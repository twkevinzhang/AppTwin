package org.apptwin.gms.fixture;

public final class CapabilityBoundary {
    private CapabilityBoundary() {
    }

    public static boolean isAlwaysUnsupported(ProbeId id) {
        return id == ProbeId.PLAY_BILLING || id == ProbeId.PLAY_INTEGRITY;
    }

    public static boolean requiresExternalVerification(ProbeId id) {
        return id == ProbeId.FCM_LOCAL_CONTRACT
                || id == ProbeId.ACCOUNT_SIGN_IN
                || id == ProbeId.MAPS_RENDERER
                || id == ProbeId.CAST
                || id == ProbeId.NEARBY;
    }
}
