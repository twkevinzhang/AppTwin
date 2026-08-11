package com.lody.virtual.client.hook.proxies.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CallingPackageUidResolverTest {

    private static final int HOST_UID = 11062;
    private static final int CURRENT_GMS_VUID = 110002;
    private static final int SHOPEE_VUID = 110005;

    @Test
    public void restoresGroupBitsFromBinderCallerAppId() {
        assertEquals(SHOPEE_VUID, CallingPackageUidResolver.restoreRequestedUid(
                10005, HOST_UID, SHOPEE_VUID));
    }

    @Test
    public void mapsHostUidToLogicalBinderCaller() {
        assertEquals(SHOPEE_VUID, CallingPackageUidResolver.restoreRequestedUid(
                HOST_UID, HOST_UID, SHOPEE_VUID));
    }

    @Test
    public void keepsUnrelatedUidUnchanged() {
        assertEquals(10042, CallingPackageUidResolver.restoreRequestedUid(
                10042, HOST_UID, SHOPEE_VUID));
    }

    @Test
    public void distinguishesCurrentVirtualUserFromPhysicalUserZero() {
        assertTrue(CallingPackageUidResolver.belongsToCurrentVirtualUser(
                SHOPEE_VUID, CURRENT_GMS_VUID));
        assertFalse(CallingPackageUidResolver.belongsToCurrentVirtualUser(
                10042, CURRENT_GMS_VUID));
        assertFalse(CallingPackageUidResolver.belongsToCurrentVirtualUser(
                -1, CURRENT_GMS_VUID));
    }

    @Test
    public void acceptsVirtualCallerOnlyInsideCurrentGroup() {
        assertEquals(SHOPEE_VUID, CallingPackageUidResolver.trustedCallerVUid(
                CURRENT_GMS_VUID, SHOPEE_VUID, true));
        assertEquals(CURRENT_GMS_VUID, CallingPackageUidResolver.trustedCallerVUid(
                CURRENT_GMS_VUID, 210005, true));
        assertEquals(CURRENT_GMS_VUID, CallingPackageUidResolver.trustedCallerVUid(
                CURRENT_GMS_VUID, SHOPEE_VUID, false));
    }

    @Test
    public void onlyPinnedGmsMayResolveAmbiguousHostUidFromItsOwnGroup() {
        assertTrue(CallingPackageUidResolver.mayExposeCurrentGroupCandidates(
                HOST_UID, HOST_UID, CURRENT_GMS_VUID, CURRENT_GMS_VUID,
                "com.google.android.gms", true));
        assertFalse(CallingPackageUidResolver.mayExposeCurrentGroupCandidates(
                HOST_UID, HOST_UID, CURRENT_GMS_VUID, SHOPEE_VUID,
                "com.google.android.gms", true));
        assertFalse(CallingPackageUidResolver.mayExposeCurrentGroupCandidates(
                HOST_UID, HOST_UID, CURRENT_GMS_VUID, CURRENT_GMS_VUID,
                "com.google.android.youtube", true));
        assertFalse(CallingPackageUidResolver.mayExposeCurrentGroupCandidates(
                HOST_UID, HOST_UID, CURRENT_GMS_VUID, CURRENT_GMS_VUID,
                "com.google.android.gms", false));
    }
}
