package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PendingIntentsOwnershipTest {
    @Test
    public void deletedUserOwnershipNeverMatchesAnotherOrReusedIdentity() {
        assertTrue(PendingIntents.belongsToUser(3, 3));
        assertFalse(PendingIntents.belongsToUser(4, 3));
        assertFalse(PendingIntents.belongsToUser(null, 3));
    }
}
