package com.lody.virtual.server.pm;

/**
 * Orders a trusted GmsCore suspension so durable package absence is committed only after every
 * package/user-scoped background owner has been cleared. Every cleanup is deliberately replayed
 * when the package is already absent, allowing a process restart to repair a partial attempt.
 */
final class TrustedGmsSuspensionCoordinator {

    interface Operations {
        void killProcesses();
        void clearJobs() throws Exception;
        void clearNotifications() throws Exception;
        void clearPendingIntents() throws Exception;
        boolean isInstalled();
        void commitUnbind() throws Exception;
        boolean hasBackgroundOwnership();
    }

    private TrustedGmsSuspensionCoordinator() {
    }

    static boolean suspend(Operations operations) {
        try {
            operations.killProcesses();
            operations.clearJobs();
            operations.clearNotifications();
            operations.clearPendingIntents();
            if (operations.isInstalled()) {
                operations.commitUnbind();
            }
            return !operations.isInstalled() && !operations.hasBackgroundOwnership();
        } catch (Throwable failure) {
            return false;
        }
    }
}
