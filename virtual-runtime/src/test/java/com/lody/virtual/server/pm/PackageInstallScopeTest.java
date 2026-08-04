package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PackageInstallScopeTest {
    @Test
    public void firstScopedInstallMarksOnlyRequestedUserInstalled() {
        assertFalse(PackageInstallScope.isInstalledOnFirstInstall(0, 7));
        assertTrue(PackageInstallScope.isInstalledOnFirstInstall(7, 7));
        assertFalse(PackageInstallScope.isInstalledOnFirstInstall(8, 7));
        assertEquals(7, PackageInstallScope.notificationUserId(7));
    }

    @Test
    public void legacyGlobalInstallStillTargetsPrimaryUser() {
        assertTrue(PackageInstallScope.isInstalledOnFirstInstall(
                0, PackageInstallScope.GLOBAL_USER_ID));
        assertFalse(PackageInstallScope.isInstalledOnFirstInstall(
                7, PackageInstallScope.GLOBAL_USER_ID));
        assertEquals(PackageInstallScope.GLOBAL_USER_ID,
                PackageInstallScope.notificationUserId(PackageInstallScope.GLOBAL_USER_ID));
    }

    @Test
    public void equalRevisionUserBindingDoesNotSnapshotOrReplaceSharedCode() {
        assertFalse(PackageInstallScope.requiresCodeSnapshot(42, 42));
        assertTrue(PackageInstallScope.requiresCodeSnapshot(42, 43));
        assertTrue(PackageInstallScope.requiresCodeSnapshot(43, 42));
    }
}
