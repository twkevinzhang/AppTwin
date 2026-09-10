package com.lody.virtual.client;

/**
 * Process-local guard for the identity assigned to a Stub process.
 *
 * <p>The guard deliberately fails closed once guest binding starts. A Stub process may only be
 * reattached to a replacement server token while it still has not loaded guest code and the
 * previous server token is no longer alive.</p>
 */
public final class StubProcessOwner {

    public static final String REASON_ACCEPTED = "accepted";
    public static final String REASON_IDEMPOTENT = "idempotent";
    public static final String REASON_REATTACHED = "reattached";
    public static final String REASON_INVALID_REQUEST = "invalid_request";
    public static final String REASON_IDENTITY_MISMATCH = "identity_mismatch";
    public static final String REASON_TOKEN_STILL_ALIVE = "token_still_alive";
    public static final String REASON_GUEST_ALREADY_BOUND = "guest_already_bound";

    public interface TokenLiveness {
        boolean isAlive(Object token);
    }

    public static final class Identity {
        private final int vuid;
        private final String packageName;
        private final String processName;
        private final long generation;
        private final int reportedUidOverride;
        private final Object serverToken;

        private Identity(int vuid, String packageName, String processName, long generation,
                         int reportedUidOverride, Object serverToken) {
            this.vuid = vuid;
            this.packageName = packageName;
            this.processName = processName;
            this.generation = generation;
            this.reportedUidOverride = reportedUidOverride;
            this.serverToken = serverToken;
        }

        public int getVuid() {
            return vuid;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getProcessName() {
            return processName;
        }

        public long getGeneration() {
            return generation;
        }

        public int getReportedUidOverride() {
            return reportedUidOverride;
        }

        public Object getServerToken() {
            return serverToken;
        }

        private boolean hasSameLogicalProcess(int requestedVuid, String requestedPackage,
                                              String requestedProcess) {
            return vuid == requestedVuid
                    && packageName.equals(requestedPackage)
                    && processName.equals(requestedProcess);
        }

        private boolean exactlyMatches(int requestedVuid, String requestedPackage,
                                       String requestedProcess, long requestedGeneration,
                                       int requestedReportedUidOverride, Object requestedToken) {
            return hasSameLogicalProcess(requestedVuid, requestedPackage, requestedProcess)
                    && generation == requestedGeneration
                    && reportedUidOverride == requestedReportedUidOverride
                    && tokensEqual(serverToken, requestedToken);
        }
    }

    public static final class ClaimResult {
        private final boolean accepted;
        private final String reason;
        private final Identity currentIdentity;

        private ClaimResult(boolean accepted, String reason, Identity currentIdentity) {
            this.accepted = accepted;
            this.reason = reason;
            this.currentIdentity = currentIdentity;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getReason() {
            return reason;
        }

        public Identity getCurrentIdentity() {
            return currentIdentity;
        }
    }

    private final TokenLiveness tokenLiveness;
    private Identity currentIdentity;
    private boolean guestBound;

    public StubProcessOwner(TokenLiveness tokenLiveness) {
        if (tokenLiveness == null) {
            throw new IllegalArgumentException("tokenLiveness must not be null");
        }
        this.tokenLiveness = tokenLiveness;
    }

    public synchronized ClaimResult claim(int vuid, String packageName, String processName,
                                          long generation, Object serverToken) {
        return claim(vuid, packageName, processName, generation, -1, serverToken);
    }

    public synchronized ClaimResult claim(int vuid, String packageName, String processName,
                                          long generation, int reportedUidOverride,
                                          Object serverToken) {
        if (serverToken == null || isEmpty(packageName) || isEmpty(processName)) {
            return result(false, REASON_INVALID_REQUEST);
        }

        if (currentIdentity == null) {
            currentIdentity = new Identity(vuid, packageName, processName, generation,
                    reportedUidOverride, serverToken);
            notifyAll();
            return result(true, REASON_ACCEPTED);
        }

        if (currentIdentity.exactlyMatches(vuid, packageName, processName, generation,
                reportedUidOverride, serverToken)) {
            return result(true, REASON_IDEMPOTENT);
        }

        if (guestBound) {
            return result(false, REASON_GUEST_ALREADY_BOUND);
        }

        if (!currentIdentity.hasSameLogicalProcess(vuid, packageName, processName)) {
            return result(false, REASON_IDENTITY_MISMATCH);
        }

        if (tokenLiveness.isAlive(currentIdentity.serverToken)) {
            return result(false, REASON_TOKEN_STILL_ALIVE);
        }

        currentIdentity = new Identity(vuid, packageName, processName, generation,
                reportedUidOverride, serverToken);
        notifyAll();
        return result(true, REASON_REATTACHED);
    }

    public synchronized void markGuestBound() {
        if (currentIdentity != null) {
            guestBound = true;
        }
    }

    public synchronized boolean isGuestBound() {
        return guestBound;
    }

    public synchronized Identity snapshot() {
        return currentIdentity;
    }

    /** Waits on the claim condition up to one fixed deadline; it never polls. */
    public synchronized Identity awaitIdentity(long timeoutMillis) {
        if (currentIdentity != null || timeoutMillis <= 0) return currentIdentity;
        long timeoutNanos = Math.min(timeoutMillis, Long.MAX_VALUE / 1_000_000L)
                * 1_000_000L;
        long deadlineNanos = System.nanoTime() + timeoutNanos;
        while (currentIdentity == null) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) break;
            long waitMillis = remainingNanos / 1_000_000L;
            int waitNanos = (int) (remainingNanos % 1_000_000L);
            try {
                wait(waitMillis, waitNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return currentIdentity;
    }

    private ClaimResult result(boolean accepted, String reason) {
        return new ClaimResult(accepted, reason, currentIdentity);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static boolean tokensEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
