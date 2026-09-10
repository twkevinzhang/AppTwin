package com.lody.virtual.server.am;

import com.lody.virtual.client.stub.VASettings;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrphanStubTaskPolicyTest {
    private static final String HOST = "org.apptwin";

    @Test
    public void removesOnlyExactInRangeStubActivityOwnedByHost() {
        String stub = VASettings.STUB_ACTIVITY + "$C3";

        assertTrue(OrphanStubTaskPolicy.shouldRemove(
                HOST, HOST, stub, null, null, false, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(
                HOST, HOST, stub, null, null, true, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(
                HOST, "other", stub, null, null, false, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(
                HOST, HOST, VASettings.STUB_ACTIVITY + "$C50", null, null, false, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(
                HOST, HOST, VASettings.STUB_ACTIVITY + "$C3suffix", null, null, false, 50));
    }

    @Test
    public void removesAllExactPhysicalGuestActivityFamiliesButNeverMainActivity() {
        assertFalse(OrphanStubTaskPolicy.shouldRemove(HOST,
                HOST, "org.apptwin.MainActivity", null, null, false, 50));
        assertTrue(OrphanStubTaskPolicy.shouldRemove(HOST,
                HOST, VASettings.STUB_DIALOG + "$C3", null, null, false, 50));
        assertTrue(OrphanStubTaskPolicy.shouldRemove(HOST,
                HOST, VASettings.STUB_EXCLUDE_FROM_RECENT_ACTIVITY + "$C3",
                null, null, false, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(HOST,
                HOST, VASettings.STUB_DIALOG + "$C50", null, null, false, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(HOST,
                "other", VASettings.STUB_EXCLUDE_FROM_RECENT_ACTIVITY + "$C3",
                null, null, false, 50));
    }

    @Test
    public void topComponentCanProveOrphanWhenBaseIsUnavailable() {
        String top = VASettings.STUB_ACTIVITY + "$C0";
        assertTrue(OrphanStubTaskPolicy.shouldRemove(
                HOST, null, null, HOST, top, false, 50));
    }
}
