package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleLocationServicePolicyTest {

    @Test
    public void blocksOnlyGmsLocationServicesWhenPermissionIsDenied() {
        assertTrue(GoogleLocationServicePolicy.shouldBlock(
                "com.google.android.gms",
                "com.google.android.location.internal.server.GoogleLocationService",
                "com.google.android.location.internal.GMS_NLP",
                false));
        assertTrue(GoogleLocationServicePolicy.shouldBlock(
                "com.google.android.gms",
                "com.google.android.gms.semanticlocation.service.SemanticLocationService",
                "com.google.android.gms.semanticlocation.ACTION_LOCATION",
                false));

        assertFalse(GoogleLocationServicePolicy.shouldBlock(
                "com.google.android.gms",
                "com.google.android.location.internal.server.GoogleLocationService",
                "com.google.android.location.internal.GMS_NLP",
                true));
        assertFalse(GoogleLocationServicePolicy.shouldBlock(
                "com.google.android.gms",
                "com.google.android.gms.chimera.PersistentApiService",
                "com.google.android.gms.phenotype.service.START",
                false));
        assertFalse(GoogleLocationServicePolicy.shouldBlock(
                "example.guest",
                "example.guest.location.BackgroundService",
                "example.guest.LOCATION",
                false));
    }
}
