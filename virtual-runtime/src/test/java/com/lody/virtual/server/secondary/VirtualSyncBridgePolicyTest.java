package com.lody.virtual.server.secondary;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualSyncBridgePolicyTest {
    @Test public void onlyExactManagerAndFixedSyncTransactionsAreAccepted() {
        assertTrue(VirtualSyncBridgePolicy.accepts(7, 7, "android.content.ISyncAdapter", 2, 31));
        assertTrue(VirtualSyncBridgePolicy.accepts(7, 7, "android.content.ISyncAdapter", 3, 31));
        assertFalse(VirtualSyncBridgePolicy.accepts(8, 7, "android.content.ISyncAdapter", 2, 31));
        assertFalse(VirtualSyncBridgePolicy.accepts(7, 7, "android.accounts.IAccountAuthenticator", 2, 31));
        assertFalse(VirtualSyncBridgePolicy.accepts(7, 7, "android.content.ISyncAdapter", 1, 31));
        assertFalse(VirtualSyncBridgePolicy.accepts(7, 7, "android.content.ISyncAdapter", 4, 31));
        assertFalse(VirtualSyncBridgePolicy.accepts(0, 0, "android.content.ISyncAdapter", 2, 31));
    }
}
