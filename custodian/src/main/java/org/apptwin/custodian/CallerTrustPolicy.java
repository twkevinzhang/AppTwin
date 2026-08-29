package org.apptwin.custodian;

/** Pure policy kept separate so all fail-closed branches can be covered on the JVM. */
final class CallerTrustPolicy {
    private CallerTrustPolicy() {
    }

    static boolean isTrusted(int callingUid, int hostPackageUid, boolean signaturesMatch) {
        return callingUid >= 0
                && callingUid == hostPackageUid
                && signaturesMatch;
    }
}
