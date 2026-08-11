package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class KeystoreAliasPolicyTest {
    @Test
    public void sameGuestAliasIsDifferentAcrossPackagesAndUsers() {
        String facebook = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, "KeyAttestation");
        String line = KeystoreAliasPolicy.toPhysicalAlias(
                "jp.naver.line.android", 1, "KeyAttestation");
        String secondSpace = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 2, "KeyAttestation");

        assertFalse(facebook.equals(line));
        assertFalse(facebook.equals(secondSpace));
        assertEquals("KeyAttestation", KeystoreAliasPolicy.toGuestAlias(
                "com.facebook.lite", 1, facebook));
    }

    @Test
    public void physicalAliasIsNotDoublePrefixed() {
        String physical = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, "KeyAttestation");

        assertEquals(physical, KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, physical));
    }

    @Test
    public void guestCanOnlyEnumerateItsOwnPhysicalAliases() {
        String facebook = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, "KeyAttestation");
        String line = KeystoreAliasPolicy.toPhysicalAlias(
                "jp.naver.line.android", 1, "RSA_KEY");

        assertTrue(KeystoreAliasPolicy.isOwnedBy("com.facebook.lite", 1, facebook));
        assertFalse(KeystoreAliasPolicy.isOwnedBy("com.facebook.lite", 1, line));
        assertNull(KeystoreAliasPolicy.toGuestAlias(
                "com.facebook.lite", 1, "KeyAttestation"));
    }

    @Test
    public void uninstallSelectsOnlyAliasesOwnedByTheRemovedGuest() {
        String facebookOne = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, "KeyAttestation");
        String facebookTwo = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 2, "KeyAttestation");
        String line = KeystoreAliasPolicy.toPhysicalAlias(
                "jp.naver.line.android", 1, "RSA_KEY");

        assertEquals(Arrays.asList(facebookOne), KeystoreAliasPolicy.ownedAliases(
                "com.facebook.lite", 1,
                Arrays.asList("XiaomiPassport", facebookOne, facebookTwo, line)));
    }

    @Test
    public void userRemovalSelectsEveryPackageOwnedByOnlyThatUser() {
        String facebookOne = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, "KeyAttestation");
        String lineOne = KeystoreAliasPolicy.toPhysicalAlias(
                "jp.naver.line.android", 1, "RSA_KEY");
        String facebookTwo = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 2, "KeyAttestation");

        assertEquals(Arrays.asList(facebookOne, lineOne),
                KeystoreAliasPolicy.ownedAliasesForUser(1,
                        Arrays.asList(facebookOne, lineOne, facebookTwo, "host-key")));
    }
}
