package com.lody.virtual.server.am;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LinePushDeliveryDiagnosticsTest {
    @After
    public void resetRateLimit() {
        LinePushDeliveryDiagnostics.resetRateLimitForTest();
    }

    @Test
    public void diagnosticEventsAreBoundedWithinOneMinute() {
        LinePushDeliveryDiagnostics.resetRateLimitForTest();

        for (int index = 0; index < 256; index++) {
            assertTrue(LinePushDeliveryDiagnostics.takeEventPermit(1_000L + index));
        }
        assertFalse(LinePushDeliveryDiagnostics.takeEventPermit(2_000L));
    }

    @Test
    public void diagnosticBudgetReopensAfterOneMinute() {
        LinePushDeliveryDiagnostics.resetRateLimitForTest();
        assertTrue(LinePushDeliveryDiagnostics.takeEventPermit(1_000L));

        assertTrue(LinePushDeliveryDiagnostics.takeEventPermit(61_000L));
    }
}
