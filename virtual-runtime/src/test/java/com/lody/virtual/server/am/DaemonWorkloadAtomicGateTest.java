package com.lody.virtual.server.am;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DaemonWorkloadAtomicGateTest {

    @Test
    public void readinessRequiresOpenGateAtExpectedEpoch() {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());

        assertFalse(gate.isOpenAt(0L));
        gate.reopen();
        long currentEpoch = gate.reopenEpoch();

        assertTrue(gate.isOpenAt(currentEpoch));
        assertFalse(gate.isOpenAt(currentEpoch + 1L));
    }
    @Test
    public void freshEngineRejectsBackgroundAcquisitionUntilVisibleSessionReopens() {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());

        assertTrue(gate.isShutdownCommitted());
        assertFalse(gate.tryBeginWorkloadAcquisition());

        gate.reopen();
        assertTrue(gate.tryBeginWorkloadAcquisition());
    }

    @Test
    public void mutationAfterSnapshotRejectsCommitWithoutRunningAction() {
        Object lock = new Object();
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(lock);
        gate.reopen();
        long expected = gate.generation();
        gate.workloadChanged();
        AtomicBoolean actionRan = new AtomicBoolean();

        boolean committed = gate.runIfStillIdle(expected,
                () -> idle(gate.generation()),
                () -> {
                    actionRan.set(true);
                    return true;
                });

        assertFalse(committed);
        assertFalse(actionRan.get());
    }

    @Test
    public void matchingReliableIdleGenerationCommitsGateAndRunsAction() {
        Object lock = new Object();
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(lock);
        gate.reopen();
        long expected = gate.generation();
        AtomicBoolean actionRan = new AtomicBoolean();

        boolean committed = gate.runIfStillIdle(expected,
                () -> idle(expected),
                () -> {
                    actionRan.set(true);
                    return true;
                });

        assertTrue(committed);
        assertTrue(actionRan.get());
        assertTrue(gate.isShutdownCommitted());
        assertFalse(gate.tryBeginWorkloadAcquisition());
    }

    @Test
    public void slowStopActionDoesNotHoldRuntimeGateLock() throws Exception {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());
        gate.reopen();
        long expected = gate.generation();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> stop = executor.submit(() -> gate.runIfStillIdle(
                    expected,
                    () -> idle(expected),
                    () -> {
                        actionStarted.countDown();
                        try {
                            return releaseAction.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }));
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS));

            Future<Long> gateProbe = executor.submit(gate::reopenEpoch);
            gateProbe.get(1, TimeUnit.SECONDS);

            releaseAction.countDown();
            assertTrue(stop.get(1, TimeUnit.SECONDS));
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void failedStopDoesNotCloseGate() {
        Object lock = new Object();
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(lock);
        gate.reopen();

        assertFalse(gate.runIfStillIdle(gate.generation(),
                () -> idle(gate.generation()), () -> false));

        assertFalse(gate.isShutdownCommitted());
        assertTrue(gate.tryBeginWorkloadAcquisition());
    }

    @Test
    public void teardownMutationRemainsAllowedWhileClosedAndReopenRestoresAcquisition() {
        Object lock = new Object();
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(lock);
        gate.reopen();
        long expected = gate.generation();
        assertTrue(gate.runIfStillIdle(expected, () -> idle(expected), () -> true));
        long committedGeneration = gate.generation();

        // Teardown records generation directly; it does not pass the acquisition guard.
        gate.workloadChanged();
        assertTrue(gate.generation() > committedGeneration);
        assertFalse(gate.tryBeginWorkloadAcquisition());

        gate.reopen();
        assertFalse(gate.isShutdownCommitted());
        assertTrue(gate.tryBeginWorkloadAcquisition());
    }

    @Test
    public void boundedWaitReleasesLockUntilVisibleSessionReopens() throws Exception {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());
        long observedReopenEpoch = gate.reopenEpoch();
        CountDownLatch waitingThreadStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> opened = executor.submit(() -> {
                waitingThreadStarted.countDown();
                return gate.awaitOpenAfter(observedReopenEpoch, 1_000L);
            });
            assertTrue(waitingThreadStarted.await(1, TimeUnit.SECONDS));

            gate.reopen();

            assertTrue(opened.get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void zeroTimeoutDoesNotWaitOnClosedGate() {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());

        assertFalse(gate.awaitOpenAfter(gate.reopenEpoch(), 0L));
    }

    @Test
    public void staleOpenSessionDoesNotSatisfyNextVisibleStartHandshake() {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());
        gate.reopen();
        long observedReopenEpoch = gate.reopenEpoch();

        assertTrue(gate.tryBeginWorkloadAcquisition());
        assertFalse(gate.awaitOpenAfter(observedReopenEpoch, 0L));

        gate.reopen();

        assertTrue(gate.awaitOpenAfter(observedReopenEpoch, 0L));
    }

    @Test
    public void futureEpochCannotBeSatisfiedByCurrentOpenSession() {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());
        gate.reopen();

        assertFalse(gate.awaitOpenAfter(gate.reopenEpoch() + 1L, 0L));
    }

    private static DaemonWorkloadSnapshot idle(long generation) {
        return new DaemonWorkloadSnapshot(generation, 0, 0, 0, 0, 0, 0, 0, true);
    }
}
