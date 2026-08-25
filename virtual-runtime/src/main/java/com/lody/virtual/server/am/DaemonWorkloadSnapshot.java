package com.lody.virtual.server.am;

/**
 * Immutable point-in-time view of the virtual runtime work that requires the engine daemon.
 *
 * <p>An unreliable observation deliberately counts as work. Stopping the daemon after Android
 * rejects a task query would be a false-negative that can terminate an otherwise active clone.</p>
 */
public final class DaemonWorkloadSnapshot {
    private final long workloadGeneration;
    private final int gmsDesiredUserCount;
    private final int activeGuestTaskCount;
    private final int activeGuestActivityCount;
    private final int activeVirtualServiceCount;
    private final int pendingPreparedLaunchCount;
    private final int keepAliveBindingCount;
    private final int lineLeaseCount;
    private final boolean observationReliable;

    DaemonWorkloadSnapshot(long workloadGeneration, int gmsDesiredUserCount,
            int activeGuestTaskCount,
            int activeGuestActivityCount, int activeVirtualServiceCount,
            int pendingPreparedLaunchCount, int keepAliveBindingCount, int lineLeaseCount,
            boolean observationReliable) {
        if (workloadGeneration < 0L) {
            throw new IllegalArgumentException("workloadGeneration must be non-negative");
        }
        this.workloadGeneration = workloadGeneration;
        this.gmsDesiredUserCount = nonNegative(gmsDesiredUserCount);
        this.activeGuestTaskCount = nonNegative(activeGuestTaskCount);
        this.activeGuestActivityCount = nonNegative(activeGuestActivityCount);
        this.activeVirtualServiceCount = nonNegative(activeVirtualServiceCount);
        this.pendingPreparedLaunchCount = nonNegative(pendingPreparedLaunchCount);
        this.keepAliveBindingCount = nonNegative(keepAliveBindingCount);
        this.lineLeaseCount = nonNegative(lineLeaseCount);
        this.observationReliable = observationReliable;
    }

    public long getWorkloadGeneration() { return workloadGeneration; }

    public int getGmsDesiredUserCount() { return gmsDesiredUserCount; }

    public int getActiveGuestTaskCount() { return activeGuestTaskCount; }

    public int getActiveGuestActivityCount() { return activeGuestActivityCount; }

    public int getActiveVirtualServiceCount() { return activeVirtualServiceCount; }

    public int getPendingPreparedLaunchCount() { return pendingPreparedLaunchCount; }

    public int getKeepAliveBindingCount() { return keepAliveBindingCount; }

    public int getLineLeaseCount() { return lineLeaseCount; }

    public boolean isObservationReliable() { return observationReliable; }

    public boolean hasNonActivityWorkload() {
        return gmsDesiredUserCount > 0
                || activeVirtualServiceCount > 0
                || pendingPreparedLaunchCount > 0
                || keepAliveBindingCount > 0
                || lineLeaseCount > 0;
    }

    public boolean hasWorkload() {
        return !observationReliable
                || hasNonActivityWorkload()
                || activeGuestTaskCount > 0
                || activeGuestActivityCount > 0;
    }

    private static int nonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("workload counts must be non-negative");
        return value;
    }
}
