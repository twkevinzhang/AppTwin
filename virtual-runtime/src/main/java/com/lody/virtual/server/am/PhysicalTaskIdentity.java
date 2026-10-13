package com.lody.virtual.server.am;

/** Stable physical task identity, with independent evidence that the task still has activities. */
final class PhysicalTaskIdentity {
    private PhysicalTaskIdentity() {}

    static int canonicalTaskId(int sdk, int persistentId, int taskId) {
        int canonical = sdk >= 29 ? taskId : persistentId;
        return canonical >= 0 ? canonical : -1;
    }

    static int runningTaskId(int sdk, int legacyId, int persistentId,
                             int taskId, boolean running, int activityCount) {
        if (sdk >= 29) {
            return running && activityCount > 0 ? canonicalTaskId(sdk, persistentId, taskId) : -1;
        }
        // Before TaskInfo (API 29), id >= 0 is the platform's running-task indicator.
        return legacyId >= 0 ? canonicalTaskId(sdk, persistentId, taskId) : -1;
    }
}
