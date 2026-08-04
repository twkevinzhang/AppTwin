package com.lody.virtual.server.pm.installer;

/** Small state guard kept Android-free so commit lifecycle behavior is directly unit-testable. */
final class PackageInstallerSessionState {
    private boolean commitRequested;
    private boolean finished;

    void requestCommit(boolean destroyed) {
        if (destroyed || finished) {
            throw new IllegalStateException("Session is no longer active");
        }
        if (commitRequested) {
            throw new IllegalStateException("Session commit already requested");
        }
        commitRequested = true;
    }

    boolean markFinished() {
        if (finished) {
            return false;
        }
        finished = true;
        return true;
    }
}
