package com.lody.virtual.server.am;

/** A missing recent-task entry alone cannot discard a confirmed, live guest task. */
final class TaskHistoryRetentionPolicy {
    private TaskHistoryRetentionPolicy() {
    }

    static boolean hasEligibleOwner(int taskId, int taskUserId, int activityTaskId,
            int activityUserId, int processUserId, boolean marked,
            boolean currentGenerationAlive) {
        return taskId == activityTaskId && taskUserId == activityUserId
                && taskUserId == processUserId && !marked && currentGenerationAlive;
    }

    static boolean shouldRetain(int taskId, int physicalTaskId, boolean liveOwner,
            String hostPackage, String basePackage, String baseClass,
            String topPackage, String topClass, int stubCount) {
        return taskId == physicalTaskId && liveOwner && hostPackage != null
                && (OrphanStubTaskPolicy.isExactStubActivity(
                        hostPackage, basePackage, baseClass, stubCount)
                    || OrphanStubTaskPolicy.isExactStubActivity(
                        hostPackage, topPackage, topClass, stubCount));
    }
}
