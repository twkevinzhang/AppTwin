package com.lody.virtual.client.stub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DaemonForegroundNotificationPolicyTest {
    @Test
    public void supportedPlatformPublishesForAllMissingOrUnpromotedStates() {
        assertTrue(DaemonForegroundNotificationPolicy.shouldPublish(false, true, false));
        assertTrue(DaemonForegroundNotificationPolicy.shouldPublish(false, true, true));
        assertTrue(DaemonForegroundNotificationPolicy.shouldPublish(true, true, false));
        assertFalse(DaemonForegroundNotificationPolicy.shouldPublish(true, true, true));
    }

    @Test
    public void unsupportedPlatformPreservesForegroundFlagBehavior() {
        assertTrue(DaemonForegroundNotificationPolicy.shouldPublish(false, false, false));
        assertFalse(DaemonForegroundNotificationPolicy.shouldPublish(true, false, false));
    }
}
