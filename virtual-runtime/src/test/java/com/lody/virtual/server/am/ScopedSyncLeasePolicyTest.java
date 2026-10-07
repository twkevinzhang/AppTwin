package com.lody.virtual.server.am;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScopedSyncLeasePolicyTest {
    @Test public void wrongUserGenerationRemovedAccountClosedGateAndDeadlineFailClosed() {
        assertTrue(ScopedSyncLeasePolicy.valid(3, 3, 9, 9, true, true, true, 100, 120000));
        assertFalse(ScopedSyncLeasePolicy.valid(3, 4, 9, 9, true, true, true, 100, 120000));
        assertFalse(ScopedSyncLeasePolicy.valid(3, 3, 9, 10, true, true, true, 100, 120000));
        assertFalse(ScopedSyncLeasePolicy.valid(3, 3, 9, 9, false, true, true, 100, 120000));
        assertFalse(ScopedSyncLeasePolicy.valid(3, 3, 9, 9, true, false, true, 100, 120000));
        assertFalse(ScopedSyncLeasePolicy.valid(3, 3, 9, 9, true, true, false, 100, 120000));
        assertFalse(ScopedSyncLeasePolicy.valid(3, 3, 9, 9, true, true, true, 120000, 120000));
    }
}
