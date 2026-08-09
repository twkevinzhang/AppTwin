package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class EqualVersionUserBindingTest {
    @Test
    public void persistenceFailureRestoresFlag() throws Exception {
        FakeState state = new FakeState();

        try {
            EqualVersionUserBinding.bind(
                    state,
                    7,
                    () -> { throw new IOException("disk full"); });
            fail("Expected persistence failure");
        } catch (IOException expected) {
            assertEquals("disk full", expected.getMessage());
        }

        assertFalse(state.isInstalled(7));
    }

    @Test
    public void persistenceSuccessLeavesBindingInstalled() throws Exception {
        FakeState state = new FakeState();
        int[] saves = {0};

        EqualVersionUserBinding.bind(
                state,
                7,
                () -> saves[0]++);

        assertTrue(state.isInstalled(7));
        assertEquals(1, saves[0]);
    }

    @Test
    public void removalPersistenceFailureRestoresInstalledFlag() throws Exception {
        FakeState state = new FakeState();
        state.setInstalled(7, true);

        try {
            EqualVersionUserBinding.unbind(
                    state,
                    7,
                    () -> { throw new IOException("disk full"); });
            fail("Expected persistence failure");
        } catch (IOException expected) {
            assertEquals("disk full", expected.getMessage());
        }

        assertTrue(state.isInstalled(7));
    }

    private static final class FakeState implements EqualVersionUserBinding.InstalledState {
        private final Map<Integer, Boolean> installed = new HashMap<>();

        @Override
        public boolean isInstalled(int userId) {
            Boolean value = installed.get(userId);
            return value != null && value;
        }

        @Override
        public void setInstalled(int userId, boolean value) {
            installed.put(userId, value);
        }
    }
}
