package com.lody.virtual.server.accounts;

/** A retained authenticator response never authorizes a recycled numeric user's account. */
final class AccountApprovalPolicy {
    private boolean consumed;
    /** Caller holds its session lock. Both allow and reject are terminal decisions. */
    boolean consumeDecision() {
        if (consumed) return false;
        consumed = true;
        return true;
    }
    static boolean mayMutate(boolean closed, int originalSerial, Integer currentSerial) {
        return !closed && originalSerial >= 0 && currentSerial != null && originalSerial == currentSerial;
    }
}
