package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lody.virtual.client.hook.proxies.keystore.KeystoreAliasPolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GuestKeystoreStateTest {
    @Test
    public void packageCleanupDeletesOnlyOwnedAliasesAndIsIdempotent() throws Exception {
        String facebook = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, "KeyAttestation");
        String line = KeystoreAliasPolicy.toPhysicalAlias(
                "jp.naver.line.android", 1, "RSA_KEY");
        FakeStore store = new FakeStore("host-key", facebook, line);

        assertTrue(GuestKeystoreState.clearPackageState(
                store, "com.facebook.lite", 1));
        assertTrue(GuestKeystoreState.clearPackageState(
                store, "com.facebook.lite", 1));
        assertEquals(Arrays.asList("host-key", line), store.aliases());
    }

    @Test
    public void failedDeletionDoesNotReportTerminalCleanup() throws Exception {
        String facebook = KeystoreAliasPolicy.toPhysicalAlias(
                "com.facebook.lite", 1, "KeyAttestation");
        FakeStore store = new FakeStore(facebook);
        store.ignoreDelete = true;

        assertFalse(GuestKeystoreState.clearPackageState(
                store, "com.facebook.lite", 1));
    }

    private static final class FakeStore implements GuestKeystoreState.Store {
        final List<String> values;
        boolean ignoreDelete;

        FakeStore(String... aliases) {
            values = new ArrayList<>(Arrays.asList(aliases));
        }

        @Override
        public List<String> aliases() {
            return new ArrayList<>(values);
        }

        @Override
        public void delete(String alias) {
            if (!ignoreDelete) values.remove(alias);
        }
    }
}
