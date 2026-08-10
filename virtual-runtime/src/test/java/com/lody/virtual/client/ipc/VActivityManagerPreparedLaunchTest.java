package com.lody.virtual.client.ipc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VActivityManagerPreparedLaunchTest {
    @Test
    public void preparedLaunchClientIsLimitedToHostMainProcess() {
        assertTrue(VActivityManager.isPreparedActivityLaunchClientAllowed(true));
        assertFalse(VActivityManager.isPreparedActivityLaunchClientAllowed(false));
    }
}
