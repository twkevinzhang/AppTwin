package com.lody.virtual.server.pm;

/** Pure policy for mapping a shared package-code install to virtual-user state. */
final class PackageInstallScope {
    static final int GLOBAL_USER_ID = -1;

    private PackageInstallScope() {
    }

    static boolean isUserScoped(int requestedUserId) {
        return requestedUserId != GLOBAL_USER_ID;
    }

    static boolean isInstalledOnFirstInstall(int userId, int requestedUserId) {
        if (isUserScoped(requestedUserId)) {
            return userId == requestedUserId;
        }
        // Preserve the legacy global-install behavior for existing callers.
        return userId == 0;
    }

    static int notificationUserId(int requestedUserId) {
        return isUserScoped(requestedUserId) ? requestedUserId : GLOBAL_USER_ID;
    }

    static boolean requiresCodeSnapshot(long existingVersionCode, long stagedVersionCode) {
        // Equal-version installs only add the shared revision to another user; no code is written.
        return existingVersionCode != stagedVersionCode;
    }
}
