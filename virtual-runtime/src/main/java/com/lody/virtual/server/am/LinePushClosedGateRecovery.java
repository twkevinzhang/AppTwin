package com.lody.virtual.server.am;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates a bounded set of LINE push retries while DaemonService reopens the normal gate.
 * Callback execution is leased so cancellation never finishes a PendingResult while dispatch is
 * concurrently transferring it to BroadcastSystem.
 */
final class LinePushClosedGateRecovery {
    static final int MAX_PENDING = 16;
    static final long HARD_TIMEOUT_MILLIS = 4_000L;
    static final long[] RETRY_DELAYS_MILLIS = {100L, 1_500L};

    interface Cancellable {
        void cancel();
    }

    interface Scheduler {
        Cancellable schedule(Runnable runnable, long delayMillis);
    }

    interface Callbacks {
        boolean requestDaemonRecovery();

        /** Revokes a daemon start that Android accepted but has not authenticated yet. */
        void revokeDaemonRecovery();

        /** Re-enters the ordinary gated broadcast path. */
        boolean retryThroughNormalGate();

        void checkpoint(String stage);

        /** Finishes the host pending result exactly once on terminal failure. */
        void finish(String reason);
    }

    private enum State {
        PENDING,
        EXECUTING
    }

    private final Scheduler scheduler;
    private final Map<Object, Entry> pending = new LinkedHashMap<>();

    LinePushClosedGateRecovery(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    boolean defer(Object token, int userId, String packageName, Callbacks callbacks) {
        if (token == null || userId <= 0 || packageName == null || callbacks == null) {
            return false;
        }

        final Entry entry;
        synchronized (this) {
            // A second owner cannot safely share the first callback/result lifecycle.
            if (pending.containsKey(token) || pending.size() >= MAX_PENDING) {
                return false;
            }
            entry = new Entry(token, userId, packageName, callbacks);
            pending.put(token, entry);
        }

        callbacks.checkpoint("recovery-pending");
        try {
            Cancellable timeout = scheduler.schedule(
                    () -> cancelEntry(entry, "recovery-timeout"), HARD_TIMEOUT_MILLIS);
            if (!attachTimeout(entry, timeout)) {
                timeout.cancel();
                return true;
            }
            runRecoveryRequest(entry);
        } catch (RuntimeException schedulingFailure) {
            cancelEntry(entry, "recovery-schedule-failed");
        }
        return true;
    }

    void cancelPackage(String packageName) {
        cancelPackageUser(packageName, -1);
    }

    void cancelPackageUser(String packageName, int userId) {
        if (packageName == null) return;
        cancelMatching(userId, packageName, "recovery-app-stopped");
    }

    void cancelUser(int userId) {
        if (userId <= 0) return;
        cancelMatching(userId, null, "recovery-user-stopped");
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    /** Must be checked under the caller's stop/dispatch fence immediately before side effects. */
    synchronized boolean isExecutionAllowed(Object token) {
        Entry entry = pending.get(token);
        return entry != null
                && entry.state == State.EXECUTING
                && entry.cancelReason == null;
    }

    private void runRecoveryRequest(Entry entry) {
        if (!acquireExecution(entry)) return;
        final boolean accepted;
        try {
            accepted = entry.callbacks.requestDaemonRecovery();
        } catch (RuntimeException recoveryFailure) {
            finishExecution(entry, false, "recovery-request-failed", -1);
            return;
        }
        finishExecution(entry, accepted, "recovery-denied", 0);
    }

    private void scheduleAttempt(Entry entry, int attempt) {
        if (!isPending(entry)) return;
        if (attempt >= RETRY_DELAYS_MILLIS.length) {
            cancelEntry(entry, "recovery-exhausted");
            return;
        }
        Cancellable scheduled = scheduler.schedule(
                () -> runAttempt(entry, attempt), RETRY_DELAYS_MILLIS[attempt]);
        synchronized (this) {
            if (pending.get(entry.token) == entry && entry.state == State.PENDING) {
                entry.attempt = scheduled;
                return;
            }
        }
        scheduled.cancel();
    }

    private void runAttempt(Entry entry, int attempt) {
        if (!acquireExecution(entry)) return;
        entry.callbacks.checkpoint("recovery-retry-" + (attempt + 1));
        final boolean dispatched;
        try {
            dispatched = entry.callbacks.retryThroughNormalGate();
        } catch (RuntimeException retryFailure) {
            finishExecution(entry, false, "recovery-retry-failed", -1);
            return;
        }
        if (dispatched) {
            transferToBroadcastSystem(entry);
        } else {
            finishExecution(entry, true, null, attempt + 1);
        }
    }

    /** Returns with an execution lease that cancellation can mark but cannot finish. */
    private synchronized boolean acquireExecution(Entry entry) {
        if (pending.get(entry.token) != entry || entry.state != State.PENDING) return false;
        entry.state = State.EXECUTING;
        return true;
    }

    /** Completes a callback only after cancellation can no longer race ownership transfer. */
    private void finishExecution(Entry entry, boolean continuePending,
            String failureReason, int nextAttempt) {
        String finishReason = null;
        boolean scheduleNext = false;
        synchronized (this) {
            if (pending.get(entry.token) != entry || entry.state != State.EXECUTING) return;
            if (entry.cancelReason != null) {
                finishReason = entry.cancelReason;
                pending.remove(entry.token);
            } else if (!continuePending) {
                finishReason = failureReason;
                pending.remove(entry.token);
            } else {
                entry.state = State.PENDING;
                scheduleNext = true;
            }
        }
        if (finishReason != null) {
            finishOwnedEntry(entry, finishReason);
        } else if (scheduleNext) {
            try {
                scheduleAttempt(entry, nextAttempt);
            } catch (RuntimeException schedulingFailure) {
                cancelEntry(entry, "recovery-schedule-failed");
            }
        }
    }

    /** Dispatch won the lease; cancellation leaves finishing to BroadcastSystem/guard. */
    private void transferToBroadcastSystem(Entry entry) {
        synchronized (this) {
            if (pending.get(entry.token) != entry || entry.state != State.EXECUTING) return;
            pending.remove(entry.token);
        }
        cancel(entry.timeout);
        cancel(entry.attempt);
        releaseDaemonAuthorization(entry);
        entry.callbacks.checkpoint("recovery-dispatched");
    }

    private void cancelEntry(Entry entry, String reason) {
        boolean finish = false;
        synchronized (this) {
            if (pending.get(entry.token) != entry) return;
            if (entry.state == State.EXECUTING) {
                if (entry.cancelReason == null) entry.cancelReason = reason;
                return;
            }
            pending.remove(entry.token);
            finish = true;
        }
        if (finish) finishOwnedEntry(entry, reason);
    }

    private void cancelMatching(int userId, String packageName, String reason) {
        List<Entry> matches = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : pending.values()) {
                if ((userId < 0 || entry.userId == userId)
                        && (packageName == null || packageName.equals(entry.packageName))) {
                    matches.add(entry);
                }
            }
        }
        for (Entry entry : matches) cancelEntry(entry, reason);
    }

    private void finishOwnedEntry(Entry entry, String reason) {
        cancel(entry.timeout);
        cancel(entry.attempt);
        releaseDaemonAuthorization(entry);
        entry.callbacks.checkpoint(reason);
        entry.callbacks.finish(reason);
    }

    private void releaseDaemonAuthorization(Entry entry) {
        synchronized (this) {
            if (entry.daemonAuthorizationReleased) return;
            entry.daemonAuthorizationReleased = true;
        }
        entry.callbacks.revokeDaemonRecovery();
    }

    private synchronized boolean attachTimeout(Entry entry, Cancellable timeout) {
        if (pending.get(entry.token) != entry || entry.state != State.PENDING) return false;
        entry.timeout = timeout;
        return true;
    }

    private synchronized boolean isPending(Entry entry) {
        return pending.get(entry.token) == entry && entry.state == State.PENDING;
    }

    private static void cancel(Cancellable cancellable) {
        if (cancellable != null) cancellable.cancel();
    }

    private static final class Entry {
        final Object token;
        final int userId;
        final String packageName;
        final Callbacks callbacks;
        State state = State.PENDING;
        String cancelReason;
        Cancellable timeout;
        Cancellable attempt;
        boolean daemonAuthorizationReleased;

        Entry(Object token, int userId, String packageName, Callbacks callbacks) {
            this.token = token;
            this.userId = userId;
            this.packageName = packageName;
            this.callbacks = callbacks;
        }
    }
}
