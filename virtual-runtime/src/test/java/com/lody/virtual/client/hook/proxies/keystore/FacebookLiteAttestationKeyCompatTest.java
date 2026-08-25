package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
