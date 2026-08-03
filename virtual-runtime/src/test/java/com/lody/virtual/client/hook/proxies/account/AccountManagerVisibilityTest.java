package com.lody.virtual.client.hook.proxies.account;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AccountManagerVisibilityTest {

    @Test
    public void hooksModernExplicitAddAndVisibilityQueries() {
        assertEquals("addAccountExplicitlyWithVisibility",
                new AccountManagerStub.AddAccountExplicitlyWithVisibility().getMethodName());
        assertEquals("getAccountVisibility",
                new AccountManagerStub.GetAccountVisibility().getMethodName());
        assertEquals("getAccountsAndVisibilityForPackage",
                new AccountManagerStub.GetAccountsAndVisibilityForPackage().getMethodName());
        assertEquals("getPackagesAndVisibilityForAccount",
                new AccountManagerStub.GetPackagesAndVisibilityForAccount().getMethodName());
    }
}
