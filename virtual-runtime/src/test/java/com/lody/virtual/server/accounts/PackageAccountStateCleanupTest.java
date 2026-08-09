package com.lody.virtual.server.accounts;

import static org.junit.Assert.assertEquals;

import android.accounts.Account;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class PackageAccountStateCleanupTest {

    @Test
    public void quarantineAndResetRemoveOnlyPinnedGmsTypeForTheRequestedUser() {
        VAccount gmsA = account(3, "gms-a", "com.google");
        VAccount otherA = account(3, "other-a", "com.example.auth");
        VAccount gmsB = account(4, "gms-b", "com.google");
        List<VAccount> accounts = new ArrayList<>(Arrays.asList(gmsA, otherA, gmsB));
        List<VAccountManagerService.AuthTokenRecord> tokens = new ArrayList<>();

        VAccountManagerService.removePackageOwnedAccountState(
                accounts, tokens, 3, new HashSet<>(Arrays.asList("com.google")));

        assertEquals(2, accounts.size());
        assertEquals("other-a", accounts.get(0).name);
        assertEquals("gms-b", accounts.get(1).name);
        assertEquals(0, tokens.size());
        assertEquals(true, VAccountManagerService.isPackageOwnedAccountType(
                3, "com.google", 3, new HashSet<>(Arrays.asList("com.google"))));
        assertEquals(false, VAccountManagerService.isPackageOwnedAccountType(
                4, "com.google", 3, new HashSet<>(Arrays.asList("com.google"))));
    }

    private static VAccount account(int userId, String name, String type) {
        VAccount result = new VAccount(userId, new Account("fixture", "fixture"));
        result.name = name;
        result.type = type;
        return result;
    }
}
