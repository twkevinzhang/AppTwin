package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class PackageInstalledStateSnapshotTest {
    @Test
    public void restorePreservesEveryUsersInstalledFlagAfterFailedSharedUpdate() {
        FakeAccessor state = new FakeAccessor();
        state.setInstalled(0, true);
        state.setInstalled(7, false);
        state.setInstalled(8, true);
        PackageInstalledStateSnapshot snapshot = PackageInstalledStateSnapshot.capture(
                new int[] {0, 7, 8}, state);

        state.setInstalled(0, false);
        state.setInstalled(7, true);
        state.setInstalled(8, false);
        snapshot.restore(state);

        assertTrue(state.isInstalled(0));
        assertFalse(state.isInstalled(7));
        assertTrue(state.isInstalled(8));
    }

    @Test
    public void successfulSharedUpdatePreservesExistingUsersAndInstallsRequestedUser() {
        FakeAccessor state = new FakeAccessor();
        state.setInstalled(0, true);
        state.setInstalled(7, false);
        state.setInstalled(8, true);
        PackageInstalledStateSnapshot snapshot = PackageInstalledStateSnapshot.capture(
                new int[] {0, 7, 8}, state);

        // Model arbitrary mutations made while replacing the shared package setting.
        state.setInstalled(0, false);
        state.setInstalled(7, false);
        state.setInstalled(8, false);
        snapshot.restoreAfterSuccessfulUpdate(state, 7);

        assertTrue(state.isInstalled(0));
        assertTrue(state.isInstalled(7));
        assertTrue(state.isInstalled(8));
    }

    private static final class FakeAccessor implements PackageInstalledStateSnapshot.Accessor {
        private final Map<Integer, Boolean> installedByUser = new HashMap<>();

        @Override
        public boolean isInstalled(int userId) {
            Boolean installed = installedByUser.get(userId);
            return installed != null && installed;
        }

        @Override
        public void setInstalled(int userId, boolean installed) {
            installedByUser.put(userId, installed);
        }
    }
}
