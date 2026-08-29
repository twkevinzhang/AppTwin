package org.apptwin.custodian;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CallerTrustPolicyTest {
    @Test
    public void acceptsOnlyExactHostUidWithMatchingSigner() {
        assertTrue(CallerTrustPolicy.isTrusted(12001, 12001, true));
    }

    @Test
    public void rejectsDifferentUidEvenWithMatchingSigner() {
        assertFalse(CallerTrustPolicy.isTrusted(12002, 12001, true));
    }

    @Test
    public void rejectsExactHostUidWithDifferentSigner() {
        assertFalse(CallerTrustPolicy.isTrusted(12001, 12001, false));
    }

    @Test
    public void rejectsUnknownCaller() {
        assertFalse(CallerTrustPolicy.isTrusted(-1, -1, true));
    }
}
