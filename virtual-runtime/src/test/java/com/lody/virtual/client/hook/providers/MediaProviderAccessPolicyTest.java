package com.lody.virtual.client.hook.providers;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaProviderAccessPolicyTest {
    @Test public void legacyWriteGateIsNotAppliedToModernScopedStorage() {
        assertTrue(MediaProviderAccessPolicy.usesLegacyWriteCapability(31));
        assertTrue(MediaProviderAccessPolicy.usesLegacyWriteCapability(32));
        assertFalse(MediaProviderAccessPolicy.usesLegacyWriteCapability(33));
        assertFalse(MediaProviderAccessPolicy.usesLegacyWriteCapability(36));
    }
    @Test public void futureProviderEntryPointsRequireBothCapabilities() {
        assertFalse(MediaProviderAccessPolicy.allows("futureMutatingMethod", null, true, false, true));
        assertFalse(MediaProviderAccessPolicy.allows("futureMutatingMethod", null, false, true, false));
        assertTrue(MediaProviderAccessPolicy.allows("futureMutatingMethod", null, true, true, false));
        assertTrue(MediaProviderAccessPolicy.allows("getTypeAsync", null, true, false, false));
        assertTrue(MediaProviderAccessPolicy.allows("openTypedAssetFile", null, false, false, true));
    }
    @Test public void readGrantsCannotAuthorizeMutations() {
        for (String method : new String[]{"insert", "bulkInsert", "update", "delete", "call", "applyBatch"}) {
            assertFalse(method, MediaProviderAccessPolicy.allows(method, null, true, false, true));
        }
        for (String mode : new String[]{"w", "wa", "a", "rw", "rwt", "r+", null}) {
            assertFalse(String.valueOf(mode), MediaProviderAccessPolicy.allows("openFile", mode, true, false, true));
        }
    }
    @Test public void selectedReadUriIsOnlyAReadCapability() {
        assertTrue(MediaProviderAccessPolicy.allows("openFile", "r", false, false, true));
        assertTrue(MediaProviderAccessPolicy.allows("query", null, false, false, true));
        assertFalse(MediaProviderAccessPolicy.allows("openFile", "rw", false, false, true));
        assertFalse(MediaProviderAccessPolicy.allows("delete", null, false, false, true));
    }
    @Test public void writeGrantDoesNotAuthorizeReads() {
        assertTrue(MediaProviderAccessPolicy.allows("insert", null, false, true, false));
        assertTrue(MediaProviderAccessPolicy.allows("openFile", "w", false, true, false));
        assertFalse(MediaProviderAccessPolicy.allows("query", null, false, true, false));
        assertFalse(MediaProviderAccessPolicy.allows("openFile", "rw", false, true, false));
        assertFalse(MediaProviderAccessPolicy.allows("call", null, false, true, false));
    }
    @Test public void bothDirectionsSupportReadWriteAndUnknownMutatingCalls() {
        assertTrue(MediaProviderAccessPolicy.allows("openFile", "rw", true, true, false));
        assertTrue(MediaProviderAccessPolicy.allows("applyBatch", null, true, true, false));
        assertTrue(MediaProviderAccessPolicy.allows("call", null, true, true, false));
    }
}
