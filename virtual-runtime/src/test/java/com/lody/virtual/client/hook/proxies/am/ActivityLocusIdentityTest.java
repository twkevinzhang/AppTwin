package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ActivityLocusIdentityTest {
    @Test
    public void attributesGuestLocusUpdateToPhysicalHostPackage() {
        assertArrayEquals(new String[]{"org.apptwin", "com.google.android.apps.dynamite.PeopleActivity"},
                ActivityManagerStub.locusComponentIdentity(
                        "com.google.android.apps.dynamite.PeopleActivity", "org.apptwin"));
    }

    @Test
    public void rejectsIncompleteIdentity() {
        assertNull(ActivityManagerStub.locusComponentIdentity(null, "org.apptwin"));
        assertNull(ActivityManagerStub.locusComponentIdentity("example.Activity", null));
        assertNull(ActivityManagerStub.locusComponentIdentity("example.Activity", ""));
    }
}
