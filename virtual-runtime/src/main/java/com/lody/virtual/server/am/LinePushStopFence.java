package com.lody.virtual.server.am;

import java.util.HashMap;
import java.util.Map;

/**
 * LINE-only stop generation fence shared by recovery admission and delayed guard dispatch.
 *
 * <p>A permit remains valid only until the matching package/user is stopped. A scoped stop also
 * rejects new admission until its destructive work has completed.</p>
 */
final class LinePushStopFence {
    static final int ALL_USERS = -1;

    private final Map<String, Long> epochs = new HashMap<>();
    private final Map<String, Integer> stopping = new HashMap<>();

    synchronized Permit acquire(String packageName, int userId) {
        if (!LinePushBroadcastPolicy.LINE_PACKAGE.equals(packageName) || userId <= 0
                || isStoppingLocked(packageName, userId)) {
            return null;
        }
        return new Permit(packageName, userId,
                epochLocked(key(packageName, ALL_USERS)),
                epochLocked(key(packageName, userId)));
    }

    synchronized boolean isCurrent(Permit permit) {
        return permit != null
                && !isStoppingLocked(permit.packageName, permit.userId)
                && permit.packageEpoch == epochLocked(key(permit.packageName, ALL_USERS))
                && permit.userEpoch == epochLocked(key(permit.packageName, permit.userId));
    }

    synchronized StopScope begin(String packageName, int userId) {
        if (!LinePushBroadcastPolicy.LINE_PACKAGE.equals(packageName)
                || (userId != ALL_USERS && userId <= 0)) {
            return StopScope.NONE;
        }
        String scopeKey = key(packageName, userId);
        epochs.put(scopeKey, epochLocked(scopeKey) + 1L);
        stopping.put(scopeKey, countLocked(scopeKey) + 1);
        return new StopScope(scopeKey);
    }

    synchronized void end(StopScope scope) {
        if (scope == null || scope == StopScope.NONE) return;
        int count = countLocked(scope.key);
        if (count <= 1) {
            stopping.remove(scope.key);
        } else {
            stopping.put(scope.key, count - 1);
        }
    }

    private boolean isStoppingLocked(String packageName, int userId) {
        return countLocked(key(packageName, ALL_USERS)) > 0
                || countLocked(key(packageName, userId)) > 0;
    }

    private long epochLocked(String key) {
        Long epoch = epochs.get(key);
        return epoch == null ? 0L : epoch;
    }

    private int countLocked(String key) {
        Integer count = stopping.get(key);
        return count == null ? 0 : count;
    }

    private static String key(String packageName, int userId) {
        return packageName + '#' + userId;
    }

    static final class Permit {
        final String packageName;
        final int userId;
        final long packageEpoch;
        final long userEpoch;

        Permit(String packageName, int userId, long packageEpoch, long userEpoch) {
            this.packageName = packageName;
            this.userId = userId;
            this.packageEpoch = packageEpoch;
            this.userEpoch = userEpoch;
        }
    }

    static final class StopScope {
        static final StopScope NONE = new StopScope(null);
        final String key;

        StopScope(String key) {
            this.key = key;
        }
    }
}
