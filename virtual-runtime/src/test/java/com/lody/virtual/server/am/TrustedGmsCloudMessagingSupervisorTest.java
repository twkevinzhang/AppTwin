package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lody.virtual.remote.TrustedGmsCloudMessagingState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TrustedGmsCloudMessagingSupervisorTest {

    @Test
    public void acceptedStartBecomesConnectedOnlyAfterAuthenticatedSignal() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.processAlive = true;
        fixture.runtime.bindingAlive = true;

        assertTrue(fixture.supervisor.ensureForUser(7));
        assertEquals(1, fixture.supervisor.desiredUserCount());
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                fixture.supervisor.getState(7).phase);

        fixture.supervisor.onConnected(7);

        TrustedGmsCloudMessagingState connected = fixture.supervisor.getState(7);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_CONNECTED, connected.phase);
        assertEquals(fixture.scheduler.now, connected.lastConnectedAtMillis);
        assertEquals(0, connected.retryAttempt);
    }

    @Test
    public void retriesAreBoundedAndBackedOff() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.acceptStart = false;

        assertFalse(fixture.supervisor.ensureForUser(7));
        assertEquals(5_000L, fixture.scheduler.lastDelay());
        for (int attempt = 1; attempt <= 6; attempt++) {
            fixture.scheduler.runNext();
        }

        TrustedGmsCloudMessagingState state = fixture.supervisor.getState(7);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DEGRADED, state.phase);
        assertEquals("RETRY_EXHAUSTED", state.failureCode);
        assertEquals(6, state.retryAttempt);
        assertEquals(7, fixture.runtime.startCalls);
        assertEquals(300_000L, fixture.scheduler.maxScheduledDelay);
    }

    @Test
    public void stopInvalidatesOldGenerationAndPreventsRestart() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.acceptStart = false;

        fixture.supervisor.ensureForUser(7);
        long generation = fixture.supervisor.getState(7).generation;
        assertTrue(fixture.supervisor.stopForUser(7));
        assertEquals(0, fixture.supervisor.desiredUserCount());
        fixture.scheduler.runAllIncludingCancelled();

        TrustedGmsCloudMessagingState state = fixture.supervisor.getState(7);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DISABLED, state.phase);
        assertTrue(state.generation > generation);
        assertEquals(1, fixture.runtime.startCalls);
        assertEquals(1, fixture.runtime.stopCalls);
    }

    @Test
    public void exactDesiredUsersDoNotReviveDisabledButInstalledUser() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.installed.add(9);

        assertTrue(fixture.supervisor.reconcileDesiredUsers(new int[]{7}));

        assertEquals(1, fixture.supervisor.desiredUserCount());
        assertEquals(1, fixture.runtime.startCalls);
        assertEquals(1, fixture.runtime.startedUsers.size());
        assertTrue(fixture.runtime.startedUsers.contains(7));
        assertFalse(fixture.runtime.startedUsers.contains(9));
        assertTrue(fixture.runtime.stoppedUsers.contains(9));
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                fixture.supervisor.getState(7).phase);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DISABLED,
                fixture.supervisor.getState(9).phase);
    }

    @Test
    public void genericReconcileReplaysOnlyDurableDesiredUsersAfterRestart() {
        FakeDesiredUserStore store = new FakeDesiredUserStore();
        store.users.add(7);
        FakeRuntime runtime = new FakeRuntime();
        runtime.installed.add(7);
        runtime.installed.add(9);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, new FakeScheduler(), store);

        supervisor.reconcile();

        assertEquals(1, supervisor.desiredUserCount());
        assertTrue(runtime.startedUsers.contains(7));
        assertFalse(runtime.startedUsers.contains(9));
        assertTrue(runtime.stoppedUsers.contains(9));
    }

    @Test
    public void exactReplacementCancelsRemovedUsersOldRetryGeneration() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.installed.add(9);
        fixture.runtime.acceptStart = false;
        assertFalse(fixture.supervisor.reconcileDesiredUsers(new int[]{7, 9}));

        fixture.supervisor.reconcileDesiredUsers(new int[]{7});
        fixture.scheduler.runAllIncludingCancelled();

        assertEquals(1L,
                fixture.runtime.startedUsers.stream().filter(id -> id == 9).count());
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DISABLED,
                fixture.supervisor.getState(9).phase);
        assertFalse(fixture.supervisor.durableDesiredUsers().contains(9));
    }

    @Test
    public void exactDesiredUsersIgnoreInvalidAndDuplicateIdsWithoutBroadeningAuthority() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.installed.add(9);

        assertTrue(fixture.supervisor.reconcileDesiredUsers(new int[]{7, 7, 0, -1}));

        assertEquals(1, fixture.runtime.startCalls);
        assertEquals(1, fixture.supervisor.durableDesiredUsers().size());
        assertTrue(fixture.supervisor.durableDesiredUsers().contains(7));
        assertFalse(fixture.runtime.startedUsers.contains(9));
    }

    @Test
    public void desiredCountSnapshotDoesNotAcquireSupervisorMonitor() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        FakeRuntime runtime = new FakeRuntime() {
            @Override public boolean startCloudMessaging(int userId) {
                super.startCloudMessaging(userId);
                startEntered.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;
            }
        };
        runtime.installed.add(7);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, new FakeScheduler());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> ensure = executor.submit(() -> supervisor.ensureForUser(7));
            assertTrue(startEntered.await(1, TimeUnit.SECONDS));

            Future<Integer> count = executor.submit(supervisor::desiredUserCount);
            assertEquals(1, count.get(1, TimeUnit.SECONDS).intValue());

            releaseStart.countDown();
            assertTrue(ensure.get(1, TimeUnit.SECONDS));
        } finally {
            releaseStart.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void runtimeStartDoesNotHoldSupervisorMonitorAgainstConcurrentStop() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        FakeRuntime runtime = new FakeRuntime() {
            @Override public boolean startCloudMessaging(int userId) {
                super.startCloudMessaging(userId);
                startEntered.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;
            }
        };
        runtime.installed.add(7);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, new FakeScheduler());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> ensure = executor.submit(() -> supervisor.ensureForUser(7));
            assertTrue(startEntered.await(1, TimeUnit.SECONDS));

            Future<Boolean> stop = executor.submit(() -> supervisor.stopForUser(7));
            assertTrue(stop.get(1, TimeUnit.SECONDS));
            assertEquals(0, supervisor.desiredUserCount());

            releaseStart.countDown();
            assertFalse(ensure.get(1, TimeUnit.SECONDS));
        } finally {
            releaseStart.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void runtimeStopDoesNotHoldSupervisorMonitorAgainstConcurrentEnsure() throws Exception {
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        FakeRuntime runtime = new FakeRuntime() {
            @Override public void stopCloudMessaging(int userId) {
                super.stopCloudMessaging(userId);
                stopEntered.countDown();
                try {
                    releaseStop.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        runtime.installed.add(7);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, new FakeScheduler());
        assertTrue(supervisor.ensureForUser(7));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> stop = executor.submit(() -> supervisor.stopForUser(7));
            assertTrue(stopEntered.await(1, TimeUnit.SECONDS));

            Future<Boolean> ensure = executor.submit(() -> supervisor.ensureForUser(7));
            assertTrue(ensure.get(1, TimeUnit.SECONDS));
            assertEquals(1, supervisor.desiredUserCount());

            releaseStop.countDown();
            assertTrue(stop.get(1, TimeUnit.SECONDS));
        } finally {
            releaseStop.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void reconcileDiscoveryDoesNotHoldSupervisorMonitorAgainstConcurrentStop()
            throws Exception {
        CountDownLatch discoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        FakeRuntime runtime = new FakeRuntime() {
            @Override public int[] installedUserIds() {
                discoveryEntered.countDown();
                try {
                    releaseDiscovery.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return super.installedUserIds();
            }
        };
        runtime.installed.add(7);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, new FakeScheduler());
        assertTrue(supervisor.ensureForUser(7));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> reconcile = executor.submit(supervisor::reconcile);
            assertTrue(discoveryEntered.await(1, TimeUnit.SECONDS));

            Future<Boolean> stop = executor.submit(() -> supervisor.stopForUser(7));
            assertTrue(stop.get(1, TimeUnit.SECONDS));
            assertEquals(0, supervisor.desiredUserCount());

            releaseDiscovery.countDown();
            assertFalse(reconcile.get(1, TimeUnit.SECONDS));
        } finally {
            releaseDiscovery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void retryRuntimeStartDoesNotHoldSupervisorMonitorAgainstConcurrentStop()
            throws Exception {
        CountDownLatch retryEntered = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        FakeRuntime runtime = new FakeRuntime() {
            @Override public boolean startCloudMessaging(int userId) {
                boolean retry = startCalls > 0;
                boolean accepted = super.startCloudMessaging(userId);
                if (retry) {
                    retryEntered.countDown();
                    try {
                        releaseRetry.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return accepted;
            }
        };
        runtime.installed.add(7);
        runtime.acceptStart = false;
        FakeScheduler scheduler = new FakeScheduler();
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, scheduler);
        assertFalse(supervisor.ensureForUser(7));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> retry = executor.submit(scheduler::runNext);
            assertTrue(retryEntered.await(1, TimeUnit.SECONDS));

            Future<Boolean> stop = executor.submit(() -> supervisor.stopForUser(7));
            assertTrue(stop.get(1, TimeUnit.SECONDS));

            releaseRetry.countDown();
            retry.get(1, TimeUnit.SECONDS);
            assertEquals(0, supervisor.desiredUserCount());
        } finally {
            releaseRetry.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void durableDesiredMissingPackageStillCountsAsDaemonWorkload() {
        FakeDesiredUserStore store = new FakeDesiredUserStore();
        store.users.add(7);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(
                        new FakeRuntime(), new FakeScheduler(), store);

        assertFalse(supervisor.reconcile());

        assertEquals(1, supervisor.desiredUserCount());
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DEGRADED,
                supervisor.getState(7).phase);
        assertEquals("NOT_INSTALLED", supervisor.getState(7).failureCode);
    }

    @Test
    public void exactSaveFailureDoesNotReplaceDurableOrLiveDesiredState() {
        FakeDesiredUserStore store = new FakeDesiredUserStore();
        store.users.add(7);
        store.failSave = true;
        FakeRuntime runtime = new FakeRuntime();
        runtime.installed.add(7);
        runtime.installed.add(9);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, new FakeScheduler(), store);

        assertFalse(supervisor.reconcileDesiredUsers(new int[]{9}));

        assertEquals(1, supervisor.desiredUserCount());
        assertTrue(supervisor.durableDesiredUsers().contains(7));
        assertFalse(supervisor.durableDesiredUsers().contains(9));
        assertEquals(0, runtime.startCalls);
        assertEquals(0, runtime.stopCalls);
    }

    @Test
    public void stopSaveFailureKeepsDesiredStateAndDoesNotStopRuntime() {
        FakeDesiredUserStore store = new FakeDesiredUserStore();
        FakeRuntime runtime = new FakeRuntime();
        runtime.installed.add(7);
        TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, new FakeScheduler(), store);
        assertTrue(supervisor.ensureForUser(7));
        store.failSave = true;

        assertFalse(supervisor.stopForUser(7));

        assertEquals(1, supervisor.desiredUserCount());
        assertTrue(supervisor.durableDesiredUsers().contains(7));
        assertEquals(0, runtime.stopCalls);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                supervisor.getState(7).phase);
    }

    @Test
    public void trustedReconnectSignalDowngradesConnectedWithoutDuplicatingStart() {
        Fixture fixture = connectedFixture(41L);
        int startsBeforeReconnect = fixture.runtime.startCalls;

        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);

        TrustedGmsCloudMessagingState state = fixture.supervisor.getState(7);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING, state.phase);
        assertEquals("MCS_RECONNECT_REQUIRED", state.failureCode);
        assertEquals(startsBeforeReconnect, fixture.runtime.startCalls);
        assertEquals(30_000L, fixture.scheduler.lastDelay());
        assertEquals(1, fixture.scheduler.activeTaskCount());
    }

    @Test
    public void duplicateReconnectAndEnsureDoNotExtendDeadlineOrStartAgain() {
        Fixture fixture = connectedFixture(41L);
        int startsBeforeReconnect = fixture.runtime.startCalls;
        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);
        int scheduledAfterFirstSignal = fixture.scheduler.tasks.size();

        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_CONNECTIVITY_CHANGE);
        assertTrue(fixture.supervisor.ensureForUser(7));
        assertTrue(fixture.supervisor.reconcile());

        assertEquals(scheduledAfterFirstSignal, fixture.scheduler.tasks.size());
        assertEquals(1, fixture.scheduler.activeTaskCount());
        assertEquals(startsBeforeReconnect, fixture.runtime.startCalls);
        assertEquals("MCS_RECONNECT_REQUIRED", fixture.supervisor.getState(7).failureCode);
    }

    @Test
    public void connectedConfirmationCancelsTimeoutAndAllowsLaterReconnect() {
        Fixture fixture = connectedFixture(41L);
        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);

        fixture.scheduler.now++;
        fixture.supervisor.onConnected(7);
        fixture.scheduler.runAllIncludingCancelled();

        assertEquals(TrustedGmsCloudMessagingState.PHASE_CONNECTED,
                fixture.supervisor.getState(7).phase);
        int scheduledBeforeSecondSignal = fixture.scheduler.tasks.size();
        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);
        assertEquals(scheduledBeforeSecondSignal + 1, fixture.scheduler.tasks.size());
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                fixture.supervisor.getState(7).phase);
    }

    @Test
    public void reconnectTimeoutImmediatelyStartsOneSupervisorFallback() {
        Fixture fixture = connectedFixture(41L);
        int startsBeforeReconnect = fixture.runtime.startCalls;
        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);

        fixture.scheduler.runNext();

        assertEquals(startsBeforeReconnect + 1, fixture.runtime.startCalls);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                fixture.supervisor.getState(7).phase);
        assertEquals("MCS_RECONNECT_TIMEOUT", fixture.supervisor.getState(7).failureCode);
        assertEquals(30_000L, fixture.scheduler.lastDelay());
        assertEquals(1, fixture.scheduler.activeTaskCount());
    }

    @Test
    public void fallbackTimeoutEntersExistingBoundedRetrySequence() {
        Fixture fixture = connectedFixture(41L);
        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);
        fixture.scheduler.runNext(); // reconnect confirmation timeout -> immediate fallback

        fixture.scheduler.runNext(); // fallback connection timeout -> backed-off retry

        TrustedGmsCloudMessagingState state = fixture.supervisor.getState(7);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DEGRADED, state.phase);
        assertEquals("CONNECT_TIMEOUT", state.failureCode);
        assertEquals(1, state.retryAttempt);
        assertEquals(5_000L, fixture.scheduler.lastDelay());
    }

    @Test
    public void staleProcessGenerationCannotDowngradeConnectedState() {
        Fixture fixture = connectedFixture(41L);

        fixture.supervisor.onMcsReconnectRequired(7, 40L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);

        assertEquals(TrustedGmsCloudMessagingState.PHASE_CONNECTED,
                fixture.supervisor.getState(7).phase);
        assertEquals(0, fixture.scheduler.activeTaskCount());
    }

    @Test
    public void processDeathInvalidatesReconnectConfirmationCallback() {
        Fixture fixture = connectedFixture(41L);
        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);
        fixture.supervisor.onProcessDied(7, 41L);
        int startsBeforeStaleCallback = fixture.runtime.startCalls;

        fixture.scheduler.runAllIncludingCancelled();

        // The cancelled confirmation cannot run an immediate fallback. Only the process-death
        // retry is allowed to perform one new start.
        assertEquals(startsBeforeStaleCallback + 1, fixture.runtime.startCalls);
        assertFalse("MCS_RECONNECT_TIMEOUT".equals(
                fixture.supervisor.getState(7).failureCode));
    }

    @Test
    public void newerProcessGenerationInvalidatesOldReconnectDeadline() {
        Fixture fixture = connectedFixture(41L);
        fixture.supervisor.onMcsReconnectRequired(7, 41L,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT);
        int startsBeforeNewProcess = fixture.runtime.startCalls;

        fixture.supervisor.onProcessReady(7, 42L);
        fixture.supervisor.onBindingConnected(7, 42L);
        fixture.scheduler.runAllIncludingCancelled();

        assertEquals(startsBeforeNewProcess, fixture.runtime.startCalls);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                fixture.supervisor.getState(7).phase);
        assertTrue(fixture.supervisor.ensureForUser(7));
        assertEquals(startsBeforeNewProcess + 1, fixture.runtime.startCalls);
        assertEquals(1, fixture.scheduler.activeTaskCount());
    }

    @Test
    public void connectedSignalDuringRuntimeStartSupersedesTimeoutWithoutStoppingRuntime() {
        FakeScheduler scheduler = new FakeScheduler();
        TrustedGmsCloudMessagingSupervisor[] holder = new TrustedGmsCloudMessagingSupervisor[1];
        FakeRuntime runtime = new FakeRuntime() {
            @Override public boolean startCloudMessaging(int userId) {
                boolean accepted = super.startCloudMessaging(userId);
                processAlive = true;
                bindingAlive = true;
                holder[0].onConnected(userId);
                return accepted;
            }
        };
        runtime.installed.add(7);
        holder[0] = new TrustedGmsCloudMessagingSupervisor(runtime, scheduler);

        assertTrue(holder[0].ensureForUser(7));

        assertEquals(TrustedGmsCloudMessagingState.PHASE_CONNECTED,
                holder[0].getState(7).phase);
        assertEquals(0, runtime.stopCalls);
        assertEquals(0, scheduler.activeTaskCount());
    }

    private static Fixture connectedFixture(long processGeneration) {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.processAlive = true;
        fixture.runtime.bindingAlive = true;
        assertTrue(fixture.supervisor.ensureForUser(7));
        fixture.supervisor.onProcessReady(7, processGeneration);
        fixture.supervisor.onBindingConnected(7, processGeneration);
        fixture.supervisor.onConnected(7);
        return fixture;
    }

    private static final class Fixture {
        final FakeRuntime runtime = new FakeRuntime();
        final FakeScheduler scheduler = new FakeScheduler();
        final TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, scheduler);
    }

    private static class FakeRuntime
            implements TrustedGmsCloudMessagingSupervisor.RuntimeOperations {
        final Set<Integer> installed = new HashSet<>();
        boolean acceptStart = true;
        boolean processAlive;
        boolean bindingAlive;
        int startCalls;
        int stopCalls;
        final List<Integer> startedUsers = new ArrayList<>();
        final List<Integer> stoppedUsers = new ArrayList<>();

        @Override public boolean isInstalled(int userId) { return installed.contains(userId); }

        @Override public int[] installedUserIds() {
            return installed.stream().mapToInt(Integer::intValue).toArray();
        }

        @Override public boolean startCloudMessaging(int userId) {
            startCalls++;
            startedUsers.add(userId);
            return acceptStart;
        }

        @Override public void stopCloudMessaging(int userId) {
            stopCalls++;
            stoppedUsers.add(userId);
        }

        @Override public boolean isPersistentProcessAlive(int userId) { return processAlive; }

        @Override public boolean isPersistentBindingAlive(int userId) { return bindingAlive; }
    }

    private static final class FakeDesiredUserStore
            implements TrustedGmsCloudMessagingSupervisor.DesiredUserStore {
        final Set<Integer> users = new HashSet<>();
        boolean failSave;

        @Override public Set<Integer> load() { return new HashSet<>(users); }

        @Override public boolean save(Set<Integer> userIds) {
            if (failSave) return false;
            users.clear();
            users.addAll(userIds);
            return true;
        }
    }

    private static final class FakeScheduler
            implements TrustedGmsCloudMessagingSupervisor.Scheduler {
        final List<Task> tasks = new ArrayList<>();
        long now = 123_456L;
        long maxScheduledDelay;

        @Override public TrustedGmsCloudMessagingSupervisor.Cancellable schedule(
                Runnable runnable, long delayMillis) {
            Task task = new Task(runnable, delayMillis);
            tasks.add(task);
            maxScheduledDelay = Math.max(maxScheduledDelay, delayMillis);
            return () -> task.cancelled = true;
        }

        @Override public long currentTimeMillis() { return now; }

        long lastDelay() { return tasks.get(tasks.size() - 1).delayMillis; }

        int activeTaskCount() {
            int count = 0;
            for (Task task : tasks) {
                if (!task.cancelled && !task.ran) count++;
            }
            return count;
        }

        void runNext() {
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.cancelled && !task.ran) {
                    task.ran = true;
                    task.runnable.run();
                    return;
                }
            }
        }

        void runAllIncludingCancelled() {
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.ran) {
                    task.ran = true;
                    task.runnable.run();
                }
            }
        }

        private static final class Task {
            final Runnable runnable;
            final long delayMillis;
            boolean cancelled;
            boolean ran;

            Task(Runnable runnable, long delayMillis) {
                this.runnable = runnable;
                this.delayMillis = delayMillis;
            }
        }
    }
}
