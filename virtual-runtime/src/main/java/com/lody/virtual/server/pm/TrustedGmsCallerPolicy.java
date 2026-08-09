package com.lody.virtual.server.pm;

/** Server-side authority gate for host-only trusted GMS package operations. */
final class TrustedGmsCallerPolicy {
    private TrustedGmsCallerPolicy() {
    }

    static void enforceHost(int callingUid, int hostUid) {
        if (callingUid != hostUid) {
            throw new SecurityException("Only the AppTwin host may manage trusted GMS state");
        }
    }
}
