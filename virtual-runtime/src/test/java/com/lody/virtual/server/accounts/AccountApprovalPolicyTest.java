package com.lody.virtual.server.accounts;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccountApprovalPolicyTest {
    @Test public void pendingApprovalRequiresLiveOriginalIncarnation() {
        assertTrue(AccountApprovalPolicy.mayMutate(false, 7, 7));
        assertFalse(AccountApprovalPolicy.mayMutate(true, 7, 7));
        assertFalse(AccountApprovalPolicy.mayMutate(false, 7, null));
        assertFalse(AccountApprovalPolicy.mayMutate(false, 7, 8));
        assertFalse(AccountApprovalPolicy.mayMutate(false, -1, -1));
    }
    @Test public void terminalDecisionCannotDeleteReaddedSameNameDuringBroadcastWindow() {
        AccountApprovalPolicy approval = new AccountApprovalPolicy();
        assertTrue(approval.consumeDecision());
        // Before close, broadcasting may block and a second approval may arrive.
        assertFalse(approval.consumeDecision());
        assertFalse(approval.consumeDecision());
    }
    @Test public void rejectionAlsoConsumesDecision() {
        AccountApprovalPolicy rejection = new AccountApprovalPolicy();
        assertTrue(rejection.consumeDecision());
        assertFalse(rejection.consumeDecision());
    }
}
