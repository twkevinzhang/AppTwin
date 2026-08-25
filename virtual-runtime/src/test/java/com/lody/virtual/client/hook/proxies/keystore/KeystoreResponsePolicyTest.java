package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeystoreResponsePolicyTest {
    @Test
    public void matchesOnlyThePermanentlyInvalidatedResponseCode() {
        assertTrue(KeystoreResponsePolicy.matches(17, 17));
        assertFalse(KeystoreResponsePolicy.matches(7, 17));
    }

    @Test
    public void ignoresNonServiceExceptions() {
        assertFalse(KeystoreResponsePolicy.isPermanentlyInvalidated(
                new IllegalStateException("not a binder response")));
        assertFalse(KeystoreResponsePolicy.isPermanentlyInvalidated(null));
    }
}
