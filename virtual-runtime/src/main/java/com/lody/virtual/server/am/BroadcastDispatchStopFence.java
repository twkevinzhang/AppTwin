package com.lody.virtual.server.am;

import java.util.HashMap;
import java.util.Map;

/** Generation fence preventing delayed static broadcasts from crossing an explicit stop. */
final class BroadcastDispatchStopFence {
    static final int ALL_USERS = -1;

    private final Map<String, Long> packageEpochs = new HashMap<>();
    private final Map<String, Integer> packageStops = new HashMap<>();
    private final Map<Integer, Long> userEpochs = new HashMap<>();
    private final Map<Integer, Integer> userStops = new HashMap<>();
    private long globalEpoch;
    private int globalStops;

    synchronized Permit acquire(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty() || userId < 0
                || globalStops > 0 || isPackageStoppingLocked(packageName, userId)
                || count(userStops, userId) > 0) {
            return null;
        }
        return new Permit(packageName, userId, globalEpoch,
                epoch(packageEpochs, key(packageName, ALL_USERS)),
                epoch(packageEpochs, key(packageName, userId)),
                epoch(userEpochs, userId));
    }

    synchronized boolean isCurrent(Permit permit) {
        return permit != null
                && globalStops == 0
                && globalEpoch == permit.globalEpoch
                && !isPackageStoppingLocked(permit.packageName, permit.userId)
                && count(userStops, permit.userId) == 0
                && epoch(packageEpochs, key(permit.packageName, ALL_USERS))
                == permit.packageEpoch
                && epoch(packageEpochs, key(permit.packageName, permit.userId))
                == permit.packageUserEpoch
                && epoch(userEpochs, permit.userId) == permit.userEpoch;
    }

    synchronized StopScope beginPackage(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()
                || (userId != ALL_USERS && userId < 0)) {
            return StopScope.NONE;
        }
        String scopeKey = key(packageName, userId);
        packageEpochs.put(scopeKey, epoch(packageEpochs, scopeKey) + 1L);
        packageStops.put(scopeKey, count(packageStops, scopeKey) + 1);
        return new StopScope(Type.PACKAGE, scopeKey, userId);
    }

    synchronized StopScope beginUser(int userId) {
        if (userId < 0) return StopScope.NONE;
        userEpochs.put(userId, epoch(userEpochs, userId) + 1L);
        userStops.put(userId, count(userStops, userId) + 1);
        return new StopScope(Type.USER, null, userId);
    }

    synchronized StopScope beginAll() {
        globalEpoch++;
        globalStops++;
        return new StopScope(Type.GLOBAL, null, ALL_USERS);
    }

    synchronized void end(StopScope scope) {
        if (scope == null || scope == StopScope.NONE) return;
        switch (scope.type) {
            case PACKAGE:
                decrement(packageStops, scope.packageKey);
                break;
            case USER:
                decrement(userStops, scope.userId);
                break;
            case GLOBAL:
                if (globalStops > 0) globalStops--;
                break;
        }
    }

    private boolean isPackageStoppingLocked(String packageName, int userId) {
        return count(packageStops, key(packageName, ALL_USERS)) > 0
                || count(packageStops, key(packageName, userId)) > 0;
    }

    private static String key(String packageName, int userId) {
        return packageName + '#' + userId;
    }

    private static <K> long epoch(Map<K, Long> epochs, K key) {
        Long value = epochs.get(key);
        return value == null ? 0L : value;
    }

    private static <K> int count(Map<K, Integer> counts, K key) {
        Integer value = counts.get(key);
        return value == null ? 0 : value;
    }

    private static <K> void decrement(Map<K, Integer> counts, K key) {
        int value = count(counts, key);
        if (value <= 1) counts.remove(key); else counts.put(key, value - 1);
    }

    static final class Permit {
        final String packageName;
        final int userId;
        final long globalEpoch;
        final long packageEpoch;
        final long packageUserEpoch;
        final long userEpoch;

        Permit(String packageName, int userId, long globalEpoch, long packageEpoch,
                long packageUserEpoch, long userEpoch) {
            this.packageName = packageName;
            this.userId = userId;
            this.globalEpoch = globalEpoch;
            this.packageEpoch = packageEpoch;
            this.packageUserEpoch = packageUserEpoch;
            this.userEpoch = userEpoch;
        }
    }

    private enum Type { PACKAGE, USER, GLOBAL }

    static final class StopScope {
        static final StopScope NONE = new StopScope(null, null, ALL_USERS);
        final Type type;
        final String packageKey;
        final int userId;

        StopScope(Type type, String packageKey, int userId) {
            this.type = type;
            this.packageKey = packageKey;
            this.userId = userId;
        }
    }
}
