package org.apptwin.gms.fixture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CapabilityBoundaryTest {
    @Test
    public void billingAndIntegrityAreTheOnlyFixedUnsupportedCapabilities() {
        assertTrue(CapabilityBoundary.isAlwaysUnsupported(ProbeId.PLAY_BILLING));
        assertTrue(CapabilityBoundary.isAlwaysUnsupported(ProbeId.PLAY_INTEGRITY));
        assertFalse(CapabilityBoundary.isAlwaysUnsupported(ProbeId.FCM_LOCAL_CONTRACT));
        assertFalse(CapabilityBoundary.isAlwaysUnsupported(ProbeId.MAPS_RENDERER));
    }

    @Test
    public void externalCapabilitiesCannotBePromotedByLocalConstruction() {
        assertTrue(CapabilityBoundary.requiresExternalVerification(ProbeId.FCM_LOCAL_CONTRACT));
        assertTrue(CapabilityBoundary.requiresExternalVerification(ProbeId.ACCOUNT_SIGN_IN));
        assertTrue(CapabilityBoundary.requiresExternalVerification(ProbeId.MAPS_RENDERER));
        assertTrue(CapabilityBoundary.requiresExternalVerification(ProbeId.CAST));
        assertTrue(CapabilityBoundary.requiresExternalVerification(ProbeId.NEARBY));
        assertFalse(CapabilityBoundary.requiresExternalVerification(
                ProbeId.PLAY_SERVICES_AVAILABILITY));
    }
}
