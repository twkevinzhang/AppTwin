package com.lody.virtual.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lody.virtual.os.VUserHandle;

import org.junit.Test;

public class VirtualUserAccessPolicyTest {
    @Test
    public void guestMayUseOnlyItsOwnVirtualUserWhileHostMayManageAll() {
        int hostUid = 10_321;
        int groupBGuest = VUserHandle.getUid(8, 12_345);

        assertTrue(VirtualUserAccessPolicy.isCallerUserOrHost(groupBGuest, hostUid, 8));
        assertFalse(VirtualUserAccessPolicy.isCallerUserOrHost(groupBGuest, hostUid, 7));
        assertFalse(VirtualUserAccessPolicy.isCallerUserOrHost(groupBGuest, hostUid, -1));
        assertTrue(VirtualUserAccessPolicy.isCallerUserOrHost(hostUid, hostUid, 7));
    }
}
