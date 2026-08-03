package com.lody.virtual.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VClientImplTest {
    @Test
    public void keepsOnlyGoogleMainProcessAlive() {
        assertTrue(GoogleProcessKeepAlivePolicy.shouldKeepAlive(
                "com.google.android.gms", "com.google.android.gms"));
        assertFalse(GoogleProcessKeepAlivePolicy.shouldKeepAlive(
                "com.google.android.gms", "com.google.android.gms.persistent"));
        assertFalse(GoogleProcessKeepAlivePolicy.shouldKeepAlive(
                "com.google.android.apps.maps", "com.google.android.apps.maps"));
    }
}
