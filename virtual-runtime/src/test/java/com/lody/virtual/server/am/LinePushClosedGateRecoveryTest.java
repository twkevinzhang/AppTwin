package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LinePushClosedGateRecoveryTest {
    @Test
    public void retriesOnlyThroughNormalGateAndTransfersFinishOwnershipOnSuccess() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks callbacks = new FakeCallbacks();

        assertTrue(recovery.defer("push", 1, LINE, callbacks));
        assertEquals(1, callbacks.recoveryRequests.get());
        assertEquals(1, recovery.pendingCount());

        scheduler.runDelay(100L);
        assertEquals(1, callbacks.retries.get());
        assertEquals(1, recovery.pendingCount());

        callbacks.gateOpen.set(true);
        scheduler.runDelay(1_500L);
        assertEquals(2, callbacks.retries.get());
        assertEquals(0, callbacks.finishes.get());
        assertEquals(0, recovery.pendingCount());
        assertEquals(1, callbacks.revocations.get());
        assertTrue(callbacks.checkpoints.contains("recovery-dispatched"));
    }

    @Test
    public void duplicateTokenIsRejectedSoCallerRetainsItsOwnFinishCallback() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks first = new FakeCallbacks();
        FakeCallbacks duplicate = new FakeCallbacks();

        assertTrue(recovery.defer("same", 1, LINE, first));
        assertFalse(recovery.defer("same", 1, LINE, duplicate));
        assertEquals(1, first.recoveryRequests.get());
        assertEquals(0, duplicate.recoveryRequests.get());

        scheduler.runDelay(LinePushClosedGateRecovery.HARD_TIMEOUT_MILLIS);
        assertEquals(1, first.finishes.get());
        assertEquals(0, duplicate.finishes.get());
        assertEquals(0, recovery.pendingCount());
    }

    @Test
    public void deniedRecoveryFinishesExactlyOnce() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks callbacks = new FakeCallbacks();
        callbacks.recoveryAllowed.set(false);

        assertTrue(recovery.defer("denied", 1, LINE, callbacks));
        assertEquals(1, callbacks.finishes.get());
        assertEquals(1, callbacks.revocations.get());
        assertEquals(0, recovery.pendingCount());

        scheduler.runAllIncludingCancelled();
        assertEquals(1, callbacks.finishes.get());
        assertEquals(0, callbacks.retries.get());
    }

    @Test
    public void overflowRejectsWithoutTakingPendingResultOwnership() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        for (int index = 0; index < LinePushClosedGateRecovery.MAX_PENDING; index++) {
            assertTrue(recovery.defer("push-" + index, 1, LINE, new FakeCallbacks()));
        }

        FakeCallbacks overflow = new FakeCallbacks();
        assertFalse(recovery.defer("overflow", 1, LINE, overflow));
        assertEquals(0, overflow.recoveryRequests.get());
        assertEquals(0, overflow.finishes.get());
        assertEquals(LinePushClosedGateRecovery.MAX_PENDING, recovery.pendingCount());
    }

    @Test
    public void timeoutMakesLateRetryNoOpAndFinishesExactlyOnce() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks callbacks = new FakeCallbacks();

        assertTrue(recovery.defer("timeout", 1, LINE, callbacks));
        FakeScheduler.Task lateRetry = scheduler.firstWithDelay(100L);
        scheduler.runDelay(LinePushClosedGateRecovery.HARD_TIMEOUT_MILLIS);
        assertEquals(1, callbacks.finishes.get());
        assertEquals(1, callbacks.revocations.get());
        assertEquals(0, recovery.pendingCount());

        lateRetry.runEvenIfCancelled();
        assertEquals(0, callbacks.retries.get());
        assertEquals(1, callbacks.finishes.get());
    }

    @Test
    public void cancellationDuringTimeoutRegistrationPreventsStaleRecoveryRequest() {
        FakeCallbacks callbacks = new FakeCallbacks();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(
                (runnable, delayMillis) -> {
                    if (delayMillis == LinePushClosedGateRecovery.HARD_TIMEOUT_MILLIS) {
                        runnable.run();
                    }
                    return () -> { };
                });

        assertTrue(recovery.defer("cancel-before-request", 1, LINE, callbacks));
        assertEquals(0, callbacks.recoveryRequests.get());
        assertEquals(0, callbacks.retries.get());
        assertEquals(1, callbacks.finishes.get());
        assertEquals(0, recovery.pendingCount());
    }

    @Test
    public void executionPermitClosesImmediatelyWhenStopCancellationWinsFence() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks callbacks = new FakeCallbacks() {
            @Override public boolean requestDaemonRecovery() {
                recoveryRequests.incrementAndGet();
                assertTrue(recovery.isExecutionAllowed("permit"));
                recovery.cancelUser(1);
                assertFalse(recovery.isExecutionAllowed("permit"));
                return true;
            }
        };

        assertTrue(recovery.defer("permit", 1, LINE, callbacks));
        assertFalse(recovery.isExecutionAllowed("permit"));
        assertEquals(1, callbacks.finishes.get());
        assertEquals(0, recovery.pendingCount());
    }

    @Test
    public void explicitUserOrPackageStopCancelsPendingAndRejectsLateCallback() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks user = new FakeCallbacks();
        FakeCallbacks pkg = new FakeCallbacks();
        assertTrue(recovery.defer("user", 7, LINE, user));
        assertTrue(recovery.defer("pkg", 8, LINE, pkg));
        FakeScheduler.Task lateUser = scheduler.firstWithDelay(100L);

        recovery.cancelUser(7);
        recovery.cancelPackage(LINE);
        lateUser.runEvenIfCancelled();

        assertEquals(1, user.finishes.get());
        assertEquals(1, pkg.finishes.get());
        assertEquals(0, user.retries.get());
        assertEquals(0, recovery.pendingCount());
    }

    @Test
    public void packageUserCancellationDoesNotAffectOtherUsersOrPackages() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks lineUserOne = new FakeCallbacks();
        FakeCallbacks lineUserTwo = new FakeCallbacks();
        FakeCallbacks discordUserOne = new FakeCallbacks();
        assertTrue(recovery.defer("line-1", 1, LINE, lineUserOne));
        assertTrue(recovery.defer("line-2", 2, LINE, lineUserTwo));
        assertTrue(recovery.defer("discord-1", 1, "com.discord", discordUserOne));

        recovery.cancelPackageUser(LINE, 1);

        assertEquals(1, lineUserOne.finishes.get());
        assertEquals(0, lineUserTwo.finishes.get());
        assertEquals(0, discordUserOne.finishes.get());
        assertEquals(2, recovery.pendingCount());
    }

    @Test
    public void userAllPackageCancellationCancelsThatPackageAcrossUsersOnly() {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        FakeCallbacks lineUserOne = new FakeCallbacks();
        FakeCallbacks lineUserTwo = new FakeCallbacks();
        FakeCallbacks discordUserOne = new FakeCallbacks();
        assertTrue(recovery.defer("line-all-1", 1, LINE, lineUserOne));
        assertTrue(recovery.defer("line-all-2", 2, LINE, lineUserTwo));
        assertTrue(recovery.defer("discord-all-1", 1, "com.discord", discordUserOne));

        recovery.cancelPackageUser(LINE, -1);

        assertEquals(1, lineUserOne.finishes.get());
        assertEquals(1, lineUserTwo.finishes.get());
        assertEquals(0, discordUserOne.finishes.get());
        assertEquals(1, recovery.pendingCount());
    }

    @Test
    public void cancelDuringSuccessfulRetryWaitsForExecutionLeaseAndTransfersOwnership()
            throws Exception {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        BlockingCallbacks callbacks = new BlockingCallbacks(true);
        assertTrue(recovery.defer("race-success", 1, LINE, callbacks));
        FakeScheduler.Task retry = scheduler.firstWithDelay(100L);

        Thread worker = new Thread(retry::run);
        worker.start();
        assertTrue(callbacks.entered.await(2, TimeUnit.SECONDS));
        recovery.cancelUser(1);
        assertEquals(0, callbacks.finishes.get());

        callbacks.release.countDown();
        worker.join(2_000L);
        assertFalse(worker.isAlive());
        assertEquals(1, callbacks.retries.get());
        assertEquals(0, callbacks.finishes.get());
        assertEquals(0, recovery.pendingCount());
    }

    @Test
    public void cancelDuringRejectedRetryFinishesOnceAfterExecutionLeaseReturns()
            throws Exception {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        BlockingCallbacks callbacks = new BlockingCallbacks(false);
        assertTrue(recovery.defer("race-reject", 1, LINE, callbacks));
        FakeScheduler.Task retry = scheduler.firstWithDelay(100L);

        Thread worker = new Thread(retry::run);
        worker.start();
        assertTrue(callbacks.entered.await(2, TimeUnit.SECONDS));
        recovery.cancelPackageUser(LINE, 1);
        assertEquals(0, callbacks.finishes.get());

        callbacks.release.countDown();
        worker.join(2_000L);
        assertFalse(worker.isAlive());
        assertEquals(1, callbacks.finishes.get());
        assertEquals(0, recovery.pendingCount());
        scheduler.runAllIncludingCancelled();
        assertEquals(1, callbacks.finishes.get());
    }

    @Test
    public void timeoutDuringSuccessfulRetryWaitsForLeaseAndLeavesFinishToBroadcastSystem()
            throws Exception {
        FakeScheduler scheduler = new FakeScheduler();
        LinePushClosedGateRecovery recovery = new LinePushClosedGateRecovery(scheduler);
        BlockingCallbacks callbacks = new BlockingCallbacks(true);
        assertTrue(recovery.defer("timeout-race", 1, LINE, callbacks));
        FakeScheduler.Task retry = scheduler.firstWithDelay(100L);

        Thread worker = new Thread(retry::run);
        worker.start();
        assertTrue(callbacks.entered.await(2, TimeUnit.SECONDS));
        scheduler.runDelay(LinePushClosedGateRecovery.HARD_TIMEOUT_MILLIS);
        assertEquals(0, callbacks.finishes.get());

        callbacks.release.countDown();
        worker.join(2_000L);
        assertFalse(worker.isAlive());
        assertEquals(0, callbacks.finishes.get());
        assertEquals(0, recovery.pendingCount());
    }

    private static final String LINE = "jp.naver.line.android";

    private static class FakeCallbacks
            implements LinePushClosedGateRecovery.Callbacks {
        final AtomicBoolean recoveryAllowed = new AtomicBoolean(true);
        final AtomicBoolean gateOpen = new AtomicBoolean(false);
        final AtomicInteger recoveryRequests = new AtomicInteger();
        final AtomicInteger retries = new AtomicInteger();
        final AtomicInteger finishes = new AtomicInteger();
        final AtomicInteger revocations = new AtomicInteger();
        final List<String> checkpoints = new ArrayList<>();

        @Override public boolean requestDaemonRecovery() {
            recoveryRequests.incrementAndGet();
            return recoveryAllowed.get();
        }

        @Override public boolean retryThroughNormalGate() {
            retries.incrementAndGet();
            return gateOpen.get();
        }

        @Override public void revokeDaemonRecovery() {
            revocations.incrementAndGet();
        }

        @Override public void checkpoint(String stage) {
            checkpoints.add(stage);
        }

        @Override public void finish(String reason) {
            finishes.incrementAndGet();
        }
    }

    private static final class BlockingCallbacks extends FakeCallbacks {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean dispatchResult;

        BlockingCallbacks(boolean dispatchResult) {
            this.dispatchResult = dispatchResult;
        }

        @Override public boolean retryThroughNormalGate() {
            retries.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return dispatchResult;
        }
    }

    private static final class FakeScheduler implements LinePushClosedGateRecovery.Scheduler {
        final List<Task> tasks = new ArrayList<>();

        @Override public LinePushClosedGateRecovery.Cancellable schedule(
                Runnable runnable, long delayMillis) {
            Task task = new Task(runnable, delayMillis);
            tasks.add(task);
            return task;
        }

        void runDelay(long delayMillis) {
            Task task = firstWithDelay(delayMillis);
            task.run();
        }

        Task firstWithDelay(long delayMillis) {
            for (Task task : tasks) {
                if (!task.ran && task.delayMillis == delayMillis) return task;
            }
            throw new AssertionError("No task with delay " + delayMillis);
        }

        void runAllIncludingCancelled() {
            for (Task task : new ArrayList<>(tasks)) task.runEvenIfCancelled();
        }

        static final class Task implements LinePushClosedGateRecovery.Cancellable {
            final Runnable runnable;
            final long delayMillis;
            boolean cancelled;
            boolean ran;

            Task(Runnable runnable, long delayMillis) {
                this.runnable = runnable;
                this.delayMillis = delayMillis;
            }

            void run() {
                if (cancelled || ran) return;
                ran = true;
                runnable.run();
            }

            void runEvenIfCancelled() {
                if (ran) return;
                ran = true;
                runnable.run();
            }

            @Override public void cancel() {
                cancelled = true;
            }
        }
    }
}
