package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeystoreResultPolicyTest {

    @Test
    public void wrapsSecurityLevelBinderReturnedInsideKeyEntry() {
        assertTrue(KeystoreStub.shouldWrapResultField(
                "android.system.keystore2.IKeystoreSecurityLevel"));
    }

    @Test
    public void leavesUnrelatedKeystoreFieldsToExistingResultTraversal() {
        assertFalse(KeystoreStub.shouldWrapResultField(
                "android.system.keystore2.KeyMetadata"));
        assertFalse(KeystoreStub.shouldWrapResultField("java.lang.String"));
    }
}
