package com.lody.virtual.client.isolated;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IsolatedWorkerProcessTest {
    @Test
    public void recognizesOnlyOwnedIsolatedWorkerProcesses() {
        assertTrue(IsolatedWorkerProcess.isWorkerProcessName(
                "org.maskaccounts", "org.maskaccounts:va_isolated_0"));
        assertFalse(IsolatedWorkerProcess.isWorkerProcessName(
                "org.maskaccounts", "org.maskaccounts:p0"));
        assertFalse(IsolatedWorkerProcess.isWorkerProcessName(
                "org.maskaccounts", "other:va_isolated_0"));
        assertFalse(IsolatedWorkerProcess.isWorkerProcessName(null, null));
    }
}
