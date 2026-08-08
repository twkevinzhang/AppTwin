package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.lody.virtual.os.VUserHandle;

import org.junit.Test;

public class IsolatedProcessUidPolicyTest {

    @Test
    public void regularProcessDoesNotOverrideKernelOwnedNativeUid() {
        assertEquals(IsolatedProcessUidPolicy.NO_OVERRIDE,
                IsolatedProcessUidPolicy.reportedUidOverride(110005, false));
    }

    @Test
    public void isolatedProcessUsesAndroidIsolatedUidRange() {
        int uid = IsolatedProcessUidPolicy.reportedUidOverride(110005, true);

        assertTrue(uid >= VUserHandle.FIRST_ISOLATED_UID);
        assertTrue(uid <= VUserHandle.LAST_ISOLATED_UID);
    }

    @Test
    public void separateVirtualAccountsReceiveDistinctStableIsolatedUids() {
        int first = IsolatedProcessUidPolicy.reportedUidOverride(110005, true);
        int firstRetry = IsolatedProcessUidPolicy.reportedUidOverride(110005, true);
        int second = IsolatedProcessUidPolicy.reportedUidOverride(210005, true);

        assertEquals(first, firstRetry);
        assertNotEquals(first, second);
    }
}
