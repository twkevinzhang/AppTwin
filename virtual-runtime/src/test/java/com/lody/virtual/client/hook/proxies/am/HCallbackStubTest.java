package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HCallbackStubTest {

    @Test
    public void hostKeepAliveServiceDoesNotBindAsVirtualGuest() {
        assertFalse(GuestServiceBindingPolicy.shouldBindGuestApplication(
                "org.apptwin", "org.apptwin"));
        assertFalse(GuestServiceBindingPolicy.shouldBindGuestApplication("org.apptwin", null));
        assertTrue(GuestServiceBindingPolicy.shouldBindGuestApplication(
                "org.apptwin", "com.google.android.gms"));
    }
}
