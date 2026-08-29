package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lody.virtual.client.hook.proxies.keystore.KeystoreAliasPolicy;
import com.lody.virtual.client.hook.proxies.keystore.CustodianAliasPolicy;
import com.lody.virtual.client.hook.proxies.keystore.CustodianKeyspaceState;

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

    @Test
    public void userCleanupDeletesLegacyAndOnlyTheMappedCustodianKeyspace() throws Exception {
        String mappedKeyspace = "34480577-4e1a-449f-bc5f-b5cbc02e8a7c";
        String otherKeyspace = "7fba3b64-10ac-4541-b7e4-14706040e272";
        String legacyLine = KeystoreAliasPolicy.toPhysicalAlias(
                "jp.naver.line.android", 4, "legacy");
        String mappedLine = CustodianAliasPolicy.toPhysicalAlias(
                mappedKeyspace, "jp.naver.line.android", "stable");
        String otherLine = CustodianAliasPolicy.toPhysicalAlias(
                otherKeyspace, "jp.naver.line.android", "other");
        FakeStore store = new FakeStore("host-key", legacyLine, mappedLine, otherLine);

        assertTrue(GuestKeystoreState.clearUserState(
                store,
                4,
                new CustodianKeyspaceState.Record(mappedKeyspace, mappedKeyspace)));
        assertEquals(Arrays.asList("host-key", otherLine), store.aliases());
    }

    @Test
    public void userCleanupFailsClosedWhenCustodianAliasSurvives() throws Exception {
        String keyspace = "34480577-4e1a-449f-bc5f-b5cbc02e8a7c";
        FakeStore store = new FakeStore(CustodianAliasPolicy.toPhysicalAlias(
                keyspace, "jp.naver.line.android", "stable"));
        store.ignoreDelete = true;

        assertFalse(GuestKeystoreState.clearUserState(
                store,
                4,
                new CustodianKeyspaceState.Record(keyspace, keyspace)));
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
