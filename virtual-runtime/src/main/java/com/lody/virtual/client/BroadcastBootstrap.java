package com.lody.virtual.client;

/** Pure, bounded bootstrap policy shared by the dispatcher and guest entry point. */
public final class BroadcastBootstrap {
    public enum Action { BOOTSTRAP, WAIT, DISPATCH, REJECT }

    public static final class Lease {
        private boolean requested;

        public Action next(boolean ready, boolean permitted, long now, long deadline) {
            if (!permitted || now >= deadline) return Action.REJECT;
            if (ready) return Action.DISPATCH;
            if (requested) return Action.WAIT;
            requested = true;
            return Action.BOOTSTRAP;
        }
    }

    private final long generation;
    private final long deadline;
    private boolean consumed;

    public BroadcastBootstrap(long generation, long deadline) {
        this.generation = generation;
        this.deadline = deadline;
    }

    public synchronized boolean start(long currentGeneration, long now, boolean exactOwner) {
        if (consumed || !exactOwner || generation != currentGeneration || now >= deadline) {
            return false;
        }
        consumed = true;
        return true;
    }

    public synchronized void cancel(long expectedGeneration) {
        if (generation == expectedGeneration) consumed = true;
    }
}
