package com.lody.virtual.server.am;

/**
 * Guards the two-phase clear-top flow: choose a launch anchor first, then commit finishing old
 * activities only after the replacement has been delivered or started.
 */
final class ActivityLaunchGuard {

    private ActivityLaunchGuard() {
    }

    static <T> T selectLaunchAnchor(T sourceInTask, T topAfterMarking, T topBeforeMarking) {
        if (sourceInTask != null) {
            return sourceInTask;
        }
        if (topAfterMarking != null) {
            return topAfterMarking;
        }
        return topBeforeMarking;
    }

    static boolean shouldCommitClear(boolean targetFound, boolean delivered,
                                     boolean successorStarted) {
        return targetFound && (delivered || successorStarted);
    }
}
