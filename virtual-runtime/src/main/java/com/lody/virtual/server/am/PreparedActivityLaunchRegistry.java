package com.lody.virtual.server.am;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Tracks the bounded host wait between starting a stub and the exact guest reaching onResume. */
final class PreparedActivityLaunchRegistry {
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<Object, String> launchesByToken = new HashMap<>();

    synchronized void register(String launchId, int userId, Object expectedToken) {
        if (launchId == null || launchId.isEmpty() || entries.containsKey(launchId)) {
            throw new IllegalArgumentException("Invalid prepared launch id");
        }
        if (expectedToken != null && launchesByToken.containsKey(expectedToken)) {
            throw new IllegalArgumentException("Activity already has a prepared launch");
        }
        Entry entry = new Entry(userId, expectedToken);
        entries.put(launchId, entry);
        if (expectedToken != null) launchesByToken.put(expectedToken, launchId);
    }

    synchronized boolean attachActivity(String launchId, int userId, Object token) {
        Entry entry = entries.get(launchId);
        if (entry == null || entry.userId != userId || token == null
                || entry.token != null || launchesByToken.containsKey(token)) {
            return false;
        }
        entry.token = token;
        launchesByToken.put(token, launchId);
        return true;
    }

    synchronized void acknowledge(int userId, Object token) {
        String launchId = launchesByToken.get(token);
        Entry entry = launchId == null ? null : entries.get(launchId);
        if (entry == null || entry.userId != userId || !sameToken(entry.token, token)) return;
        entry.acknowledged = true;
        notifyAll();
    }

    synchronized boolean await(String launchId, long timeoutMs) {
        Entry entry = entries.get(launchId);
        if (entry == null || timeoutMs <= 0) {
            cancel(launchId);
            return false;
        }
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        try {
            while (!entry.acknowledged && entries.get(launchId) == entry) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) break;
                wait(remainingNanos / 1_000_000L,
                        (int) (remainingNanos % 1_000_000L));
            }
            return entry.acknowledged;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            removeLocked(launchId);
        }
    }

    synchronized void cancel(String launchId) {
        if (removeLocked(launchId)) notifyAll();
    }

    synchronized boolean cancelForUser(String launchId, int userId) {
        Entry entry = entries.get(launchId);
        if (entry == null || entry.userId != userId) return false;
        cancel(launchId);
        return true;
    }

    synchronized void cancelActivity(int userId, Object token) {
        String launchId = launchesByToken.get(token);
        Entry entry = launchId == null ? null : entries.get(launchId);
        if (entry != null && entry.userId == userId && sameToken(entry.token, token)) {
            cancel(launchId);
        }
    }

    synchronized void cancelUser(int userId) {
        boolean changed = false;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> item = iterator.next();
            if (item.getValue().userId == userId) {
                if (item.getValue().token != null) launchesByToken.remove(item.getValue().token);
                iterator.remove();
                changed = true;
            }
        }
        if (changed) notifyAll();
    }

    synchronized int pendingCount() { return entries.size(); }

    synchronized boolean isPending(String launchId, int userId) {
        Entry entry = entries.get(launchId);
        return entry != null && entry.userId == userId;
    }

    private static boolean sameToken(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private boolean removeLocked(String launchId) {
        Entry removed = entries.remove(launchId);
        if (removed == null) return false;
        if (removed.token != null) launchesByToken.remove(removed.token);
        return true;
    }

    private static final class Entry {
        final int userId;
        Object token;
        boolean acknowledged;

        Entry(int userId, Object token) {
            this.userId = userId;
            this.token = token;
        }
    }
}
