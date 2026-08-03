package com.lody.virtual.client.hook.proxies.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleRuntimePermissionsTest {

    @Test
    public void onlyGuestGsfGetsVirtualDeviceConfigRead() {
        assertTrue(GoogleRuntimePermissions.shouldGrant(
                GoogleRuntimePermissions.GOOGLE_SERVICES_FRAMEWORK,
                GoogleRuntimePermissions.READ_DEVICE_CONFIG));
        assertFalse(GoogleRuntimePermissions.shouldGrant(
                "com.google.android.apps.maps",
                GoogleRuntimePermissions.READ_DEVICE_CONFIG));
        assertFalse(GoogleRuntimePermissions.shouldGrant(
                GoogleRuntimePermissions.GOOGLE_SERVICES_FRAMEWORK,
                "android.permission.ACCESS_FINE_LOCATION"));
    }
}
