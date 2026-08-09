package com.lody.virtual.client.hook.providers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProviderAttributionIdentityPolicyTest {

    @Test
    public void internalProviderReceivesGuestPackageWithPhysicalUidHandledSeparately() {
        assertEquals("com.google.android.youtube",
                ProviderAttributionIdentityPolicy.packageName(
                        false, "com.google.android.youtube", "org.apptwin"));
    }

    @Test
    public void externalProviderAndPreBindStateRetainHostPackage() {
        assertEquals("org.apptwin",
                ProviderAttributionIdentityPolicy.packageName(
                        true, "com.google.android.youtube", "org.apptwin"));
        assertEquals("org.apptwin",
                ProviderAttributionIdentityPolicy.packageName(
                        false, null, "org.apptwin"));
    }
}
