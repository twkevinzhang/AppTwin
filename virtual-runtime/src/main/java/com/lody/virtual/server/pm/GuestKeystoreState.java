package com.lody.virtual.server.pm;

import com.lody.virtual.client.hook.proxies.keystore.KeystoreAliasPolicy;
import com.lody.virtual.client.hook.proxies.keystore.CustodianAliasPolicy;
import com.lody.virtual.client.hook.proxies.keystore.CustodianKeyspaceState;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Removes Android Keystore entries that belong to deleted guest state under the host UID. */
final class GuestKeystoreState {
    interface Store {
        List<String> aliases() throws Exception;

        void delete(String alias) throws Exception;
    }

    private GuestKeystoreState() {
    }

    static boolean clearPackageState(String packageName, int userId) {
        try {
            return clearPackageState(androidStore(), packageName, userId);
        } catch (Exception unavailable) {
            return false;
        }
    }

    static boolean hasPackageState(String packageName, int userId) {
        try {
            return !KeystoreAliasPolicy.ownedAliases(
                    packageName, userId, androidStore().aliases()).isEmpty();
        } catch (Exception unavailable) {
            // Unknown must fail closed so a removed binding cannot inherit stale key material.
            return true;
        }
    }

    static boolean clearUserState(int userId) {
        try {
            return clearUserState(
                    androidStore(), userId, CustodianKeyspaceState.readForUser(userId));
        } catch (Exception unavailable) {
            return false;
        }
    }

    static boolean clearUserState(
            Store store, int userId, CustodianKeyspaceState.Record custodian) throws Exception {
        for (String alias : KeystoreAliasPolicy.ownedAliasesForUser(
                userId, store.aliases())) {
            store.delete(alias);
        }
        if (custodian != null) {
            for (String alias : CustodianAliasPolicy.ownedAliasesForKeyspace(
                    custodian.keyspaceId, store.aliases())) {
                store.delete(alias);
            }
        }
        List<String> remaining = store.aliases();
        return KeystoreAliasPolicy.ownedAliasesForUser(userId, remaining).isEmpty()
                && (custodian == null || CustodianAliasPolicy.ownedAliasesForKeyspace(
                        custodian.keyspaceId, remaining).isEmpty());
    }

    static boolean clearPackageState(Store store, String packageName, int userId)
            throws Exception {
        for (String alias : KeystoreAliasPolicy.ownedAliases(
                packageName, userId, store.aliases())) {
            store.delete(alias);
        }
        return KeystoreAliasPolicy.ownedAliases(
                packageName, userId, store.aliases()).isEmpty();
    }

    private static Store androidStore() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return new Store() {
            @Override
            public List<String> aliases() throws Exception {
                Enumeration<String> source = keyStore.aliases();
                List<String> result = new ArrayList<>();
                while (source.hasMoreElements()) result.add(source.nextElement());
                return result;
            }

            @Override
            public void delete(String alias) throws Exception {
                keyStore.deleteEntry(alias);
            }
        };
    }
}
