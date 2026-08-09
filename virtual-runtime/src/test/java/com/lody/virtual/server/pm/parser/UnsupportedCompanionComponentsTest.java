package com.lody.virtual.server.pm.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UnsupportedCompanionComponentsTest {
    @Test
    public void exactBillingAndIntegrityComponentsAreDeniedWithoutHidingOtherServices() {
        assertTrue(UnsupportedCompanionComponents.isDenied(
                "com.android.vending", "com.android.vending.billing.InAppBillingService", true));
        assertTrue(UnsupportedCompanionComponents.isDenied(
                "com.android.vending",
                "com.google.android.finsky.integrityservice.IntegrityService", true));
        assertTrue(UnsupportedCompanionComponents.isDenied(
                "com.android.vending", "org.microg.vending.billing.PurchaseActivity", false));
        assertFalse(UnsupportedCompanionComponents.isDenied(
                "com.android.vending", "com.google.android.finsky.licensing.LicensingService", true));
        assertFalse(UnsupportedCompanionComponents.isDenied(
                "com.android.vending", "com.google.android.finsky.externalreferrer.GetInstallReferrerService", true));
        assertFalse(UnsupportedCompanionComponents.isDenied(
                "com.google.android.gms", "com.android.vending.billing.InAppBillingService", true));
    }
}
