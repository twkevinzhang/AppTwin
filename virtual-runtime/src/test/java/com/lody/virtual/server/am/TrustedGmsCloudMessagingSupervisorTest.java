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

public class TrustedGmsCloudMessagingSupervisorTest {

    @Test
    public void acceptedStartBecomesConnectedOnlyAfterAuthenticatedSignal() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.processAlive = true;
        fixture.runtime.bindingAlive = true;

        assertTrue(fixture.supervisor.ensureForUser(7));
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
        fixture.scheduler.runAllIncludingCancelled();

        TrustedGmsCloudMessagingState state = fixture.supervisor.getState(7);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DISABLED, state.phase);
        assertTrue(state.generation > generation);
        assertEquals(1, fixture.runtime.startCalls);
        assertEquals(1, fixture.runtime.stopCalls);
    }

    @Test
    public void reconciliationIsIsolatedPerInstalledVirtualUser() {
        Fixture fixture = new Fixture();
        fixture.runtime.installed.add(7);
        fixture.runtime.installed.add(9);

        fixture.supervisor.reconcile();

        assertEquals(2, fixture.runtime.startCalls);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                fixture.supervisor.getState(7).phase);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_STARTING,
                fixture.supervisor.getState(9).phase);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DISABLED,
                fixture.supervisor.getState(8).phase);
    }

    private static final class Fixture {
        final FakeRuntime runtime = new FakeRuntime();
        final FakeScheduler scheduler = new FakeScheduler();
        final TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, scheduler);
    }

    private static final class FakeRuntime
            implements TrustedGmsCloudMessagingSupervisor.RuntimeOperations {
        final Set<Integer> installed = new HashSet<>();
        boolean acceptStart = true;
        boolean processAlive;
        boolean bindingAlive;
        int startCalls;
        int stopCalls;

        @Override public boolean isInstalled(int userId) { return installed.contains(userId); }

        @Override public int[] installedUserIds() {
            return installed.stream().mapToInt(Integer::intValue).toArray();
        }

        @Override public boolean startCloudMessaging(int userId) {
            startCalls++;
            return acceptStart;
        }

        @Override public void stopCloudMessaging(int userId) { stopCalls++; }

        @Override public boolean isPersistentProcessAlive(int userId) { return processAlive; }

        @Override public boolean isPersistentBindingAlive(int userId) { return bindingAlive; }
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
