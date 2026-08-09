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

    static boolean requiresCodeSnapshot(long existingVersionCode, long stagedVersionCode,
                                        int requestedUserId) {
        // Equal-version user-scoped installs only add a binding and do not write code. Global
        // installs may replace a same-version artifact (for example a rebuilt hotfix), so they
        // must always be transactional.
        return !isUserScoped(requestedUserId) || existingVersionCode != stagedVersionCode;
    }
}
