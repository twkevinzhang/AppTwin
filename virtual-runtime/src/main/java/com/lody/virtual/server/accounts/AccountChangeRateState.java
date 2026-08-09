package com.lody.virtual.server.accounts;

import java.util.HashMap;
import java.util.Map;

/** Per-virtual-user check-in broadcast rate state. */
final class AccountChangeRateState {
    private final Map<Integer, Long> lastChangeByUserId = new HashMap<>();

    synchronized boolean recordIfElapsed(int userId, long now, long minimumIntervalMillis) {
        Long last = lastChangeByUserId.get(userId);
        if (last != null && Math.abs(now - last) <= minimumIntervalMillis) {
            return false;
        }
        lastChangeByUserId.put(userId, now);
        return true;
    }

    synchronized void put(int userId, long timestamp) {
        lastChangeByUserId.put(userId, timestamp);
    }

    synchronized void remove(int userId) {
        lastChangeByUserId.remove(userId);
    }

    synchronized void clear() {
        lastChangeByUserId.clear();
    }

    synchronized Map<Integer, Long> snapshot() {
        return new HashMap<>(lastChangeByUserId);
    }
}
