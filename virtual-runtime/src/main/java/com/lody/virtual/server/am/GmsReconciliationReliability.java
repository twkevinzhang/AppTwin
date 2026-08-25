package com.lody.virtual.server.am;

/** Tracks only the latest durable GMS reconciliation as authoritative for daemon snapshots. */
final class GmsReconciliationReliability {
    private final Object lock;
    private long generation;
    private boolean complete;

    GmsReconciliationReliability(Object lock) {
        this.lock = lock;
    }

    long begin() {
        synchronized (lock) {
            complete = false;
            return ++generation;
        }
    }

    void finish(long expectedGeneration, boolean succeeded) {
        synchronized (lock) {
            if (generation == expectedGeneration) complete = succeeded;
        }
    }

    boolean isComplete() {
        synchronized (lock) {
            return complete;
        }
    }
}
