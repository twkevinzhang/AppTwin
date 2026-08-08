package com.lody.virtual.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.lody.virtual.client.stub.StubProcessKeepAliveService;

import org.junit.Test;

public class VClientImplTest {
    @Test
    public void keepsGoogleServicePackagesAliveInEveryProcess() {
        assertTrue(GoogleProcessKeepAlivePolicy.shouldKeepAlive(
                "com.google.android.gms", "com.google.android.gms"));
        assertTrue(GoogleProcessKeepAlivePolicy.shouldKeepAlive(
                "com.google.android.gms", "com.google.android.gms.persistent"));
        assertTrue(GoogleProcessKeepAlivePolicy.shouldKeepAlive(
                "com.android.vending", "com.android.vending"));
        assertFalse(GoogleProcessKeepAlivePolicy.shouldKeepAlive(
                "com.google.android.apps.maps", "com.google.android.apps.maps"));
    }

    @Test
    public void mapsHostStubProcessToSameSlotKeepAliveService() {
        String base = StubProcessKeepAliveService.class.getName();
        assertEquals(base + "$C0", GoogleProcessKeepAlivePolicy.serviceClassNameForProcess(
                "org.apptwin", "org.apptwin:p0", 50));
        assertEquals(base + "$C49", GoogleProcessKeepAlivePolicy.serviceClassNameForProcess(
                "org.apptwin", "org.apptwin:p49", 50));
        assertNull(GoogleProcessKeepAlivePolicy.serviceClassNameForProcess(
                "org.apptwin", "org.apptwin:p50", 50));
        assertNull(GoogleProcessKeepAlivePolicy.serviceClassNameForProcess(
                "org.apptwin", "com.google.android.gms", 50));
        assertNull(GoogleProcessKeepAlivePolicy.serviceClassNameForProcess(
                "org.apptwin", "org.apptwin:px", 50));
    }
}
