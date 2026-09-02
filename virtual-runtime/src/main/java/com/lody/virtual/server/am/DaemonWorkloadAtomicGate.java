package com.lody.virtual.server.am;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Serializes workload mutations with the final idle-to-stop commit. */
final class DaemonWorkloadAtomicGate {
    private final Object lock;
    private long generation;
    private long reopenEpoch;
    // Fresh provider/job engine processes are closed until a legitimate DaemonService.onStart
    // foreground session explicitly reopens acquisition.
    private boolean shutdownCommitted = true;

    DaemonWorkloadAtomicGate(Object lock) {
        this.lock = lock;
    }

    long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    void workloadChanged() {
        synchronized (lock) {
            generation++;
        }
    }

    /** Returns false after the daemon stop has been committed. Must guard every acquisition. */
    boolean tryBeginWorkloadAcquisition() {
        synchronized (lock) {
            return !shutdownCommitted;
        }
    }

    long reopenEpoch() {
        synchronized (lock) {
            return reopenEpoch;
        }
    }

    void reopen() {
        synchronized (lock) {
            // Every legitimate Service.onStartCommand is a distinct handshake, including when
            // an older foreground session left the gate OPEN. Waiters bind to this epoch instead
            // of accepting stale OPEN state from the preceding request.
            shutdownCommitted = false;
            generation++;
            reopenEpoch++;
            lock.notifyAll();
        }
    }

    boolean awaitOpenAfter(long observedReopenEpoch, long timeoutMillis) {
        long remainingNanos = timeoutMillis * 1_000_000L;
        final long deadline = System.nanoTime() + remainingNanos;
        synchronized (lock) {
            while (shutdownCommitted || reopenEpoch <= observedReopenEpoch) {
                if (remainingNanos <= 0L) return false;
                try {
                    long millis = remainingNanos / 1_000_000L;
                    int nanos = (int) (remainingNanos % 1_000_000L);
                    lock.wait(millis, nanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    boolean isShutdownCommitted() {
        synchronized (lock) {
            return shutdownCommitted;
        }
    }

    boolean isOpenAt(long expectedReopenEpoch) {
        synchronized (lock) {
            return !shutdownCommitted && reopenEpoch == expectedReopenEpoch;
        }
    }

    boolean runIfStillIdle(long expectedGeneration,
            Supplier<DaemonWorkloadSnapshot> freshSnapshot,
            BooleanSupplier action) {
        synchronized (lock) {
            DaemonWorkloadSnapshot snapshot = freshSnapshot.get();
            if (shutdownCommitted
                    || snapshot == null
                    || snapshot.getWorkloadGeneration() != expectedGeneration
                    || generation != expectedGeneration
                    || !snapshot.isObservationReliable()
                    || snapshot.hasWorkload()) {
                return false;
            }
            // Commit the closed gate before asking Android to stop the Service, then release the
            // VAMS monitor. stopSelfResult() is a system Binder call and must never run while this
            // lock excludes every other runtime Binder request.
            shutdownCommitted = true;
            generation++;
        }

        final boolean stopped;
        try {
            stopped = action.getAsBoolean();
        } catch (Throwable error) {
            reopenAfterFailedStop();
            throw error;
        }
        if (!stopped) reopenAfterFailedStop();
        return stopped;
    }

    private void reopenAfterFailedStop() {
        synchronized (lock) {
            // A newer DaemonService start may already have reopened the gate while the stop action
            // was in flight. Preserve that newer handshake instead of publishing another epoch.
            if (!shutdownCommitted) return;
            shutdownCommitted = false;
            generation++;
            reopenEpoch++;
            lock.notifyAll();
        }
    }
}
