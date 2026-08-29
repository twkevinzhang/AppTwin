package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.SignatureException;

import org.junit.Test;

public class FacebookLiteAttestationKeyCompatTest {
    @Test
    public void appliesOnlyToFacebookLite() {
        assertTrue(FacebookLiteAttestationKeyCompat.supportsPackage("com.facebook.lite"));
        assertFalse(FacebookLiteAttestationKeyCompat.supportsPackage(
                "jp.naver.line.android"));
        assertFalse(FacebookLiteAttestationKeyCompat.supportsPackage(null));
    }

    @Test
    public void refreshesOnlyTheFacebookLiteAttestationAlias() {
        assertTrue(FacebookLiteAttestationKeyCompat.isAttestationAlias(
                "com.facebook.lite", "w6CmevIyM/PL6Q5uUDw="));
        assertFalse(FacebookLiteAttestationKeyCompat.isAttestationAlias(
                "com.facebook.lite", "another-key"));
        assertFalse(FacebookLiteAttestationKeyCompat.isAttestationAlias(
                "jp.naver.line.android", "w6CmevIyM/PL6Q5uUDw="));
    }

    @Test
    public void detectsPermanentInvalidationNestedInsideSignatureFailure() {
        PermanentInvalidation invalidation = new PermanentInvalidation();
        SignatureException signatureFailure = new SignatureException(
                "signing failed", new IllegalStateException("provider failed", invalidation));

        assertTrue(FacebookLiteAttestationKeyCompat.hasCauseOfType(
                signatureFailure, PermanentInvalidation.class));
    }

    @Test
    public void doesNotTreatOtherSignatureFailuresAsPermanentInvalidation() {
        SignatureException signatureFailure = new SignatureException(
                "signing failed", new IllegalStateException("temporary provider failure"));

        assertFalse(FacebookLiteAttestationKeyCompat.hasCauseOfType(
                signatureFailure, PermanentInvalidation.class));
        assertFalse(FacebookLiteAttestationKeyCompat.hasCauseOfType(
                signatureFailure, null));
    }

    @Test
    public void keepsThe525WarmupMappingWithoutForce() {
        FacebookLiteAttestationKeyCompat.WarmupAbi abi =
                FacebookLiteAttestationKeyCompat.warmupAbiForVersion(516101866);

        assertEquals("X.0Fs", abi.helperClass);
        assertEquals("A05", abi.providerField);
        assertFalse(abi.force);
    }

    @Test
    public void usesThe526CoordinatorAndForcesSignatureRefresh() {
        FacebookLiteAttestationKeyCompat.WarmupAbi abi =
                FacebookLiteAttestationKeyCompat.warmupAbiForVersion(516201887);

        assertEquals("X.0Fg", abi.helperClass);
        assertEquals("A05", abi.providerField);
        assertTrue(abi.force);
    }

    @Test
    public void skipsUnknownFacebookLiteWarmupMappings() {
        assertNull(FacebookLiteAttestationKeyCompat.warmupAbiForVersion(516201888));
    }

    private static final class PermanentInvalidation extends Exception {
    }
}
