package com.lody.virtual.server.am;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java lifecycle gate for service work owned by one guest process generation.
 *
 * <p>Callers enqueue work while holding their own bookkeeping lock, then invoke {@link #drain(long)}
 * only after releasing that lock. Dispatch actions are never executed while this object's monitor
 * is held. At most one thread drains a generation at a time.</p>
 */
final class ProcessLifecycle {

    enum State {
        STARTING,
        READY,
        FAILED,
        DEAD
    }

    enum TerminalReason {
        APPLICATION_BIND_FAILED,
        STARTUP_TIMEOUT,
        PROCESS_DIED,
        DISPATCH_FAILED,
        STALE_GENERATION
    }

    private final long generation;
    private final ArrayDeque<PendingServiceOperation> pending = new ArrayDeque<>();
    private State state = State.STARTING;
    private TerminalReason terminalReason;
    private boolean draining;

    ProcessLifecycle(long generation) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
    }

    long generation() {
        return generation;
    }

    synchronized State state() {
        return state;
    }

    synchronized TerminalReason terminalReason() {
        return terminalReason;
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    synchronized boolean isDraining() {
        return draining;
    }

    /**
     * Adds non-CREATE service work to this generation's FIFO.
     *
     * <p>Accepted READY work is still queued rather than dispatched inline. This lets the caller
     * leave its global lock before calling {@link #drain(long)}.</p>
     */
    boolean enqueue(PendingServiceOperation operation) {
        if (operation == null) {
            throw new NullPointerException("operation");
        }
        TerminalReason rejection = null;
        synchronized (this) {
            if (operation.generation() != generation) {
                rejection = TerminalReason.STALE_GENERATION;
            } else if (state == State.FAILED || state == State.DEAD) {
                rejection = terminalReason == null ? TerminalReason.PROCESS_DIED : terminalReason;
            } else {
                pending.addLast(operation);
                return true;
            }
        }
        operation.cancel(rejection);
        return false;
    }

    /** Accepts an Application-ready callback only for the active generation. */
    synchronized boolean markReady(long callbackGeneration) {
        if (callbackGeneration != generation || state == State.FAILED || state == State.DEAD) {
            return false;
        }
        if (state == State.STARTING) {
            state = State.READY;
        }
        return true;
    }

    boolean markFailed(long callbackGeneration, TerminalReason reason) {
        if (reason == null) {
            throw new NullPointerException("reason");
        }
        List<PendingServiceOperation> cancelled;
        synchronized (this) {
            if (callbackGeneration != generation || state == State.FAILED || state == State.DEAD) {
                return false;
            }
            state = State.FAILED;
            terminalReason = reason;
            cancelled = clearPendingLocked();
        }
        cancelAll(cancelled, reason);
        return true;
    }

    /** A startup watchdog may fail only the generation that is still waiting for Application. */
    boolean markStartupTimedOut(long callbackGeneration) {
        List<PendingServiceOperation> cancelled;
        synchronized (this) {
            if (callbackGeneration != generation || state != State.STARTING) {
                return false;
            }
            state = State.FAILED;
            terminalReason = TerminalReason.STARTUP_TIMEOUT;
            cancelled = clearPendingLocked();
        }
        cancelAll(cancelled, TerminalReason.STARTUP_TIMEOUT);
        return true;
    }

    boolean markDead(long callbackGeneration, TerminalReason reason) {
        if (reason == null) {
            throw new NullPointerException("reason");
        }
        List<PendingServiceOperation> cancelled;
        synchronized (this) {
            if (callbackGeneration != generation || state == State.DEAD) {
                return false;
            }
            state = State.DEAD;
            terminalReason = reason;
            cancelled = clearPendingLocked();
        }
        cancelAll(cancelled, reason);
        return true;
    }

    /**
     * Dispatches READY work in FIFO order. A reentrant or concurrent caller observes zero work;
     * the active drainer also consumes operations enqueued while it is running.
     *
     * @return number of operations whose dispatch action was entered by this call
     */
    int drain(long callbackGeneration) {
        synchronized (this) {
            if (callbackGeneration != generation || state != State.READY || draining) {
                return 0;
            }
            draining = true;
        }

        int dispatched = 0;
        boolean releaseDrainerOnExit = true;
        try {
            while (true) {
                PendingServiceOperation operation;
                synchronized (this) {
                    if (state != State.READY) {
                        draining = false;
                        releaseDrainerOnExit = false;
                        return dispatched;
                    }
                    operation = pending.pollFirst();
                    if (operation == null) {
                        // Release ownership before unlocking. Otherwise an enqueue followed by a
                        // concurrent drain could observe draining=true in the narrow finally gap
                        // and leave READY work stranded indefinitely.
                        draining = false;
                        releaseDrainerOnExit = false;
                        return dispatched;
                    }
                }
                try {
                    if (operation.dispatch()) {
                        dispatched++;
                    }
                } catch (Exception dispatchFailure) {
                    markFailed(generation, TerminalReason.DISPATCH_FAILED);
                    return dispatched;
                }
            }
        } finally {
            if (releaseDrainerOnExit) {
                synchronized (this) {
                    draining = false;
                }
            }
        }
    }

    private List<PendingServiceOperation> clearPendingLocked() {
        List<PendingServiceOperation> cancelled = new ArrayList<>(pending);
        pending.clear();
        return cancelled;
    }

    private static void cancelAll(List<PendingServiceOperation> operations,
                                  TerminalReason reason) {
        for (PendingServiceOperation operation : operations) {
            operation.cancel(reason);
        }
    }
}
