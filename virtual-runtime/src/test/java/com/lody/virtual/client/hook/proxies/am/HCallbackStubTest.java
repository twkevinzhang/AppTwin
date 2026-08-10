package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HCallbackStubTest {
    @Test
    public void rejectedTaskAttachmentIsConsumedWithoutGuestRewriteOrRetry() {
        assertTrue(HCallbackLaunchPolicy.shouldConsume(
                HCallbackLaunchHandling.ABORT_CONSUMED));
        assertTrue(HCallbackLaunchPolicy.shouldConsume(
                HCallbackLaunchHandling.RETRY_QUEUED));
        assertFalse(HCallbackLaunchPolicy.shouldConsume(
                HCallbackLaunchHandling.DELEGATE));
    }
}
