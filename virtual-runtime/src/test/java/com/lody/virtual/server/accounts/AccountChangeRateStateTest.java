package com.lody.virtual.server.accounts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class AccountChangeRateStateTest {
    private static final long INTERVAL = 30L * 24 * 60 * 1000;

    @Test
    public void oneUsersRateLimitNeverSuppressesAnotherUser() {
        AccountChangeRateState state = new AccountChangeRateState();

        assertTrue(state.recordIfElapsed(3, 1_000, INTERVAL));
        assertFalse(state.recordIfElapsed(3, 1_001, INTERVAL));
        assertTrue(state.recordIfElapsed(4, 1_001, INTERVAL));
    }

    @Test
    public void deleteRestartAndReuseDoNotRestorePreviousUsersRateState() {
        AccountChangeRateState beforeRestart = new AccountChangeRateState();
        assertTrue(beforeRestart.recordIfElapsed(3, 1_000, INTERVAL));
        assertTrue(beforeRestart.recordIfElapsed(4, 1_000, INTERVAL));
        beforeRestart.remove(3);
        Map<Integer, Long> persisted = beforeRestart.snapshot();

        AccountChangeRateState afterRestart = new AccountChangeRateState();
        for (Map.Entry<Integer, Long> entry : persisted.entrySet()) {
            afterRestart.put(entry.getKey(), entry.getValue());
        }

        assertTrue(afterRestart.recordIfElapsed(3, 1_001, INTERVAL));
        assertFalse(afterRestart.recordIfElapsed(4, 1_001, INTERVAL));
    }
}
