package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class KeystoreAuthorizationPolicyTest {
    @Test
    public void keepsKeyBoundToCurrentCredentialSid() {
        assertEquals(KeystoreAuthorizationPolicy.Decision.KEEP,
                KeystoreAuthorizationPolicy.evaluate(
                        Arrays.asList(11L, 22L), 11L, new long[]{33L}));
    }

    @Test
    public void keepsKeyCompatibleWithEveryCurrentBiometricSid() {
        assertEquals(KeystoreAuthorizationPolicy.Decision.KEEP,
                KeystoreAuthorizationPolicy.evaluate(
                        Arrays.asList(22L, 33L), 11L, new long[]{22L, 33L}));
    }

    @Test
    public void deletesKeyWhenCredentialAndBiometricSidsCannotMatch() {
        assertEquals(KeystoreAuthorizationPolicy.Decision.DELETE_PERMANENTLY_INVALID,
                KeystoreAuthorizationPolicy.evaluate(
                        Arrays.asList(44L, 55L), 11L, new long[]{22L, 33L}));
    }

    @Test
    public void deletesKeyWhenOneCurrentBiometricSidChanged() {
        assertEquals(KeystoreAuthorizationPolicy.Decision.DELETE_PERMANENTLY_INVALID,
                KeystoreAuthorizationPolicy.evaluate(
                        Arrays.asList(22L, 44L), 11L, new long[]{22L, 33L}));
    }

    @Test
    public void keepsKeyWhenEvidenceIsIncomplete() {
        assertEquals(KeystoreAuthorizationPolicy.Decision.KEEP,
                KeystoreAuthorizationPolicy.evaluate(null, 11L, new long[]{22L}));
        assertEquals(KeystoreAuthorizationPolicy.Decision.KEEP,
                KeystoreAuthorizationPolicy.evaluate(
                        Collections.singletonList(44L), null, new long[]{22L}));
        assertEquals(KeystoreAuthorizationPolicy.Decision.KEEP,
                KeystoreAuthorizationPolicy.evaluate(
                        Collections.singletonList(44L), 11L, null));
        assertEquals(KeystoreAuthorizationPolicy.Decision.KEEP,
                KeystoreAuthorizationPolicy.evaluate(
                        Collections.singletonList(44L), 11L, new long[0]));
    }
}
