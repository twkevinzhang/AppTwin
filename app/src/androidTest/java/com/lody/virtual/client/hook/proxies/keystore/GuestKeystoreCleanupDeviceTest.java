package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.platform.app.InstrumentationRegistry;

import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Explicit opt-in maintenance test that deletes keys for exactly one guest package and user. */
public class GuestKeystoreCleanupDeviceTest {
    private static final String OPT_IN_ARGUMENT = "guestKeyCleanupE2e";
    private static final String PACKAGE_ARGUMENT = "guestPackage";
    private static final String USER_ARGUMENT = "guestUserId";

    @Test
    public void deletesOnlyTheRequestedGuestNamespace() throws Exception {
        assumeTrue("Guest key cleanup is opt-in; pass -e " + OPT_IN_ARGUMENT + " 1",
                "1".equals(InstrumentationRegistry.getArguments()
                        .getString(OPT_IN_ARGUMENT)));
        String packageName = InstrumentationRegistry.getArguments()
                .getString(PACKAGE_ARGUMENT);
        String userValue = InstrumentationRegistry.getArguments().getString(USER_ARGUMENT);
        assumeTrue("A guest package must be supplied",
                packageName != null && !packageName.isEmpty());
        assumeTrue("A non-negative guest user must be supplied", userValue != null);
        int userId = Integer.parseInt(userValue);
        assumeTrue("A non-negative guest user must be supplied", userId >= 0);

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        List<String> owned = KeystoreAliasPolicy.ownedAliases(
                packageName, userId, Collections.list(keyStore.aliases()));
        for (String alias : owned) {
            keyStore.deleteEntry(alias);
        }

        List<String> remaining = KeystoreAliasPolicy.ownedAliases(
                packageName, userId, Collections.list(keyStore.aliases()));
        assertTrue("the requested guest namespace must be empty", remaining.isEmpty());
    }
}
