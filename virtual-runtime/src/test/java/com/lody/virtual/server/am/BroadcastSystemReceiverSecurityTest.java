package com.lody.virtual.server.am;

import android.content.Context;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BroadcastSystemReceiverSecurityTest {
    @Test
    public void internalCrossProcessReceiverIsExportedBehindHostSignaturePermission() {
        assertEquals(Context.RECEIVER_EXPORTED, BroadcastSystem.receiverFlags());
        assertEquals("org.apptwin.permission.INTERNAL_BROADCAST",
                BroadcastSystem.requiredPermission("org.apptwin", false));
        assertEquals("org.apptwin.permission.INTERNAL_BROADCAST",
                BroadcastSystem.internalBroadcastPermission("org.apptwin"));
    }

    @Test
    public void allowlistedSystemReceiverDoesNotRequireHostPermission() {
        assertNull(BroadcastSystem.requiredPermission("org.apptwin", true));
    }
}
