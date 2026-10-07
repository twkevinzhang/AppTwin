package com.lody.virtual.server.accounts;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuthenticatorBindRetryPolicyTest {
    @Test public void denialSuccessAndStartedAuthenticatorNeverRetry() {
        assertFalse(AuthenticatorBindRetryPolicy.shouldRetry(0, -2, 0, 30000, false, false));
        assertFalse(AuthenticatorBindRetryPolicy.shouldRetry(1, -2, 0, 30000, false, false));
        assertFalse(AuthenticatorBindRetryPolicy.shouldRetry(-2, -2, 0, 30000, false, true));
        assertFalse(AuthenticatorBindRetryPolicy.shouldRetry(-2, -2, 0, 30000, true, false));
    }
    @Test public void repeatedContentionTerminatesAtHardDeadline() {
        int attempts = 1;
        long now = 0;
        while (AuthenticatorBindRetryPolicy.shouldRetry(-2, -2, now, 30000, false, false)) {
            now += AuthenticatorBindRetryPolicy.DELAY_MS;
            attempts++;
        }
        assertEquals(60, attempts);
        assertEquals(29500L, now);
        assertFalse(AuthenticatorBindRetryPolicy.shouldRetry(-2, -2, 30000, 30000, false, false));
    }
    @Test public void successfulRegistrationAfterCloseRequiresCompensation() {
        assertTrue(AuthenticatorBindRetryPolicy.shouldUndoLateBind(true, 1));
        assertFalse(AuthenticatorBindRetryPolicy.shouldUndoLateBind(false, 1));
        assertFalse(AuthenticatorBindRetryPolicy.shouldUndoLateBind(true, 0));
        assertFalse(AuthenticatorBindRetryPolicy.shouldUndoLateBind(true, -2));
    }
}
