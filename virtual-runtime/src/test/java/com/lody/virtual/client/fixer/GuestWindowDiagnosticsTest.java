package com.lody.virtual.client.fixer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuestWindowDiagnosticsTest {
    @Test
    public void requiresExplicitOptIn() {
        assertTrue(GuestWindowDiagnostics.enabledValue("1"));
        assertFalse(GuestWindowDiagnostics.enabledValue(null));
        assertFalse(GuestWindowDiagnostics.enabledValue("0"));
        assertFalse(GuestWindowDiagnostics.enabledValue("true"));
        assertFalse(GuestWindowDiagnostics.permitsBatch(false, 0, 0));
    }

    @Test
    public void boundsBothActivityAndProcessLifetimes() {
        assertTrue(GuestWindowDiagnostics.permitsBatch(true, 0, 0));
        assertTrue(GuestWindowDiagnostics.permitsBatch(true, 2, 11));
        assertFalse(GuestWindowDiagnostics.permitsBatch(true, 3, 0));
        assertFalse(GuestWindowDiagnostics.permitsBatch(true, 0, 12));
        assertFalse(GuestWindowDiagnostics.permitsBatch(true, -1, 0));
        assertFalse(GuestWindowDiagnostics.permitsBatch(true, 0, -1));
    }
}
