package com.lody.virtual.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GuestDataPathMapperTest {

    @Test
    public void create_exposesCanonicalGuestPrivatePaths() {
        GuestDataPathMapper.Paths paths = GuestDataPathMapper.create("com.facebook.lite");

        assertEquals(
                "/data/user/0/com.facebook.lite",
                paths.credentialProtectedDataDir);
        assertEquals(
                "/data/user_de/0/com.facebook.lite",
                paths.deviceProtectedDataDir);
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_rejectsMissingPackageName() {
        GuestDataPathMapper.create("");
    }
}
