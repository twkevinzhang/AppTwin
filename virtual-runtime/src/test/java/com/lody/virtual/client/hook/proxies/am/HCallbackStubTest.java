package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HCallbackStubTest {

    @Test
    public void hostKeepAliveServiceDoesNotBindAsVirtualGuest() {
        assertFalse(GuestServiceBindingPolicy.shouldBindGuestApplication(
                "org.maskaccounts", "org.maskaccounts"));
        assertFalse(GuestServiceBindingPolicy.shouldBindGuestApplication("org.maskaccounts", null));
        assertTrue(GuestServiceBindingPolicy.shouldBindGuestApplication(
                "org.maskaccounts", "com.google.android.gms"));
    }
}
