package com.lody.virtual.client.hook.providers;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProviderAuthorityPolicyTest {
    @Test public void protectsAllContactsAliasesIncludingMultiAuthorityProviders() {
        assertTrue(ProviderAuthorityPolicy.contacts("com.android.contacts;contacts"));
        assertTrue(ProviderAuthorityPolicy.contacts("contacts;com.android.contacts"));
        assertTrue(ProviderAuthorityPolicy.contacts("0@com.android.contacts;call_log"));
        assertTrue(ProviderAuthorityPolicy.contacts("7@contacts"));
        assertFalse(ProviderAuthorityPolicy.contacts("com.android.contacts.attacker"));
        assertFalse(ProviderAuthorityPolicy.contacts("call_log"));
        assertFalse(ProviderAuthorityPolicy.contacts(null));
    }
    @Test public void mediaAliasIsRecognizedWithoutExpandingAuthorityScope() {
        assertTrue(ProviderAuthorityPolicy.contains("media;media_legacy", "media"));
        assertTrue(ProviderAuthorityPolicy.contains("0@media", "media"));
        assertFalse(ProviderAuthorityPolicy.contains("media_legacy", "media"));
    }
}
