package com.lody.virtual.server.accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.accounts.AccountManager;

import com.lody.virtual.os.VUserHandle;

import org.junit.Test;

public class AccountChangeBroadcastScopeTest {

    @Test
    public void accountChangesTargetOnlyTheMutatedGroupsVirtualUser() {
        VUserHandle groupA = VAccountManagerService.accountChangeBroadcastUser(3);
        VUserHandle groupB = VAccountManagerService.accountChangeBroadcastUser(4);

        assertEquals(3, groupA.getIdentifier());
        assertEquals(4, groupB.getIdentifier());
        assertNotEquals(VUserHandle.USER_ALL, groupA.getIdentifier());
        assertNotEquals(VUserHandle.USER_ALL, groupB.getIdentifier());
        assertNotEquals(groupA.getIdentifier(), groupB.getIdentifier());
    }

    @Test
    public void accountChangesNotifyModernAndLegacyListeners() {
        String[] actions = VAccountManagerService.accountChangeBroadcastActions();

        assertEquals(2, actions.length);
        assertEquals("android.accounts.action.VISIBLE_ACCOUNTS_CHANGED", actions[0]);
        assertEquals(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION, actions[1]);
    }
}
