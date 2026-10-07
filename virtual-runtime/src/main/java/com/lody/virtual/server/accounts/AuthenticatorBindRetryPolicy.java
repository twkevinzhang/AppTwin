package com.lody.virtual.server.accounts;

/** Only an explicitly reported transient contention may be retried before side effects begin. */
final class AuthenticatorBindRetryPolicy {
    static final long DEADLINE_MS = 30_000L;
    static final long DELAY_MS = 500L;
    static boolean shouldRetry(int result, int contentionCode, long now, long deadline,
                               boolean closed, boolean invoked) {
        return !closed && !invoked && result == contentionCode && now + DELAY_MS < deadline;
    }
    static boolean shouldUndoLateBind(boolean closed, int result) { return closed && result > 0; }
}
