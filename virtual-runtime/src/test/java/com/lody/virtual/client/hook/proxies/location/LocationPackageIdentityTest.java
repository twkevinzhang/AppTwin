package com.lody.virtual.client.hook.proxies.location;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LocationPackageIdentityTest {

    @Test
    public void replacesGuestPackageWithoutCorruptingAttributionOrListenerId() {
        Object[] args = new Object[]{
                "gps",
                new Object(),
                new Object(),
                "com.shopee.tw",
                "location-attribution",
                "listener-id",
        };

        int replaced = LocationPackageIdentity.replaceGuestPackage(
                args, "com.shopee.tw", "org.apptwin");

        assertEquals(1, replaced);
        assertArrayEquals(new Object[]{
                "gps",
                args[1],
                args[2],
                "org.apptwin",
                "location-attribution",
                "listener-id",
        }, args);
    }

    @Test
    public void leavesArgumentsUntouchedWhenGuestPackageIsAbsent() {
        Object[] args = new Object[]{"gps", "listener-id"};

        assertEquals(0, LocationPackageIdentity.replaceGuestPackage(
                args, "com.shopee.tw", "org.apptwin"));
        assertArrayEquals(new Object[]{"gps", "listener-id"}, args);
    }
}
