package com.lody.virtual.client.hook.providers;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaProviderIdentityTest {
    @Test
    public void mediaStoreUsesPhysicalHostAttribution() {
        assertTrue(new MediaProviderHook(null).isExternalProvider());
    }
}
