package com.lody.virtual.server.pm;

/**
 * Immutable snapshot of the installed flag for every active virtual user.
 *
 * <p>The accessor keeps this policy independent from Android's SparseArray, which also makes the
 * rollback contract directly testable on the host JVM.</p>
 */
final class PackageInstalledStateSnapshot {
    interface Accessor extends EqualVersionUserBinding.InstalledState {
    }

    private final int[] userIds;
    private final boolean[] installed;

    private PackageInstalledStateSnapshot(int[] userIds, boolean[] installed) {
        this.userIds = userIds;
        this.installed = installed;
    }

    static PackageInstalledStateSnapshot capture(int[] activeUserIds, Accessor accessor) {
        int[] userIds = activeUserIds.clone();
        boolean[] installed = new boolean[userIds.length];
        for (int i = 0; i < userIds.length; i++) {
            installed[i] = accessor.isInstalled(userIds[i]);
        }
        return new PackageInstalledStateSnapshot(userIds, installed);
    }

    void restore(Accessor accessor) {
        for (int i = 0; i < userIds.length; i++) {
            accessor.setInstalled(userIds[i], installed[i]);
        }
    }

    void restoreAfterSuccessfulUpdate(Accessor accessor, int requestedUserId) {
        restore(accessor);
        if (PackageInstallScope.isUserScoped(requestedUserId)) {
            accessor.setInstalled(requestedUserId, true);
        }
    }
}
