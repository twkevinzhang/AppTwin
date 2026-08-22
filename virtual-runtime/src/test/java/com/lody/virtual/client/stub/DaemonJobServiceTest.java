package com.lody.virtual.client.stub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class DaemonJobServiceTest {

    @Test
    public void maintenanceCompletesSynchronously() {
        assertFalse(DaemonJobService.runsAsynchronously());
    }

    @Test
    public void recoveryIntervalUsesPlatformPeriodicMinimum() {
        assertEquals(TimeUnit.MINUTES.toMillis(15),
                DaemonJobService.PERIODIC_INTERVAL_MILLIS);
    }
}
