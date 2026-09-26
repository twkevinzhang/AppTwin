package com.lody.virtual.server.am;

import static org.junit.Assert.*;

import com.lody.virtual.remote.TrustedGmsCloudMessagingState;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Regression coverage of the real supervisor's global pause, distinct from space deletion. */
public class BackgroundExecutionPolicyTest {
    @Test public void suspensionPreservesDurableUsersAndFencesCancelledRetry() {
        Fixture f = new Fixture();
        f.runtime.accept = false;
        assertFalse(f.supervisor.ensureForUser(7));
        f.supervisor.suspendRuntime();
        f.scheduler.runCapturedIncludingCancelled();
        assertEquals(1, f.runtime.starts);
        assertEquals(set(7), f.store.users);
        assertEquals(1, f.supervisor.desiredUserCount());
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DISABLED,
                f.supervisor.getState(7).phase);
    }

    @Test public void reconcileWhileSuspendedCannotStartAnyPersistedSpace() {
        Fixture f = new Fixture();
        f.supervisor.suspendRuntime();
        assertFalse(f.supervisor.reconcile());
        assertEquals(0, f.runtime.starts);
        assertEquals(set(7), f.store.users);
    }

    @Test public void resumeReplaysExactDurableAllowlistOnlyWhenReconciled() {
        Fixture f = new Fixture();
        f.supervisor.suspendRuntime();
        f.supervisor.resumeRuntime();
        assertEquals(0, f.runtime.starts);
        assertTrue(f.supervisor.reconcile());
        assertEquals(Arrays.asList(7), f.runtime.startedUsers);
        assertEquals(set(7), f.store.users);
    }

    @Test public void suspensionDuringExternalProbeCannotResurrectRuntime() {
        Fixture f = new Fixture();
        f.runtime.onInstalledProbe = f.supervisor::suspendRuntime;
        assertFalse(f.supervisor.ensureForUser(7));
        assertEquals(0, f.runtime.starts);
        f.scheduler.runCapturedIncludingCancelled();
        assertEquals(0, f.runtime.starts);
        assertEquals(set(7), f.store.users);
    }

    @Test public void suspensionDuringAcceptedStartCleansUpLateRuntime() {
        Fixture f = new Fixture();
        f.runtime.onStart = f.supervisor::suspendRuntime;
        assertFalse(f.supervisor.ensureForUser(7));
        assertTrue(f.runtime.stops >= 1);
        f.scheduler.runCapturedIncludingCancelled();
        assertEquals(1, f.runtime.starts);
        assertEquals(TrustedGmsCloudMessagingState.PHASE_DISABLED,
                f.supervisor.getState(7).phase);
    }

    @Test public void explicitSpaceStopDuringPauseRemainsStoppedAfterResume() {
        Fixture f = new Fixture();
        f.supervisor.suspendRuntime();
        assertTrue(f.supervisor.stopForUser(7));
        f.supervisor.resumeRuntime();
        assertTrue(f.supervisor.reconcile());
        assertEquals(0, f.runtime.starts);
        assertTrue(f.store.users.isEmpty());
    }

    @Test public void oldGateEpochCannotAuthorizeWorkAfterOffOnCycle() {
        DaemonWorkloadAtomicGate gate = new DaemonWorkloadAtomicGate(new Object());
        gate.reopen();
        long oldEpoch = gate.reopenEpoch();
        gate.close();
        assertFalse(gate.tryBeginWorkloadAcquisition());
        assertFalse(gate.isOpenAt(oldEpoch));
        gate.reopen();
        assertFalse(gate.isOpenAt(oldEpoch));
        assertTrue(gate.isOpenAt(gate.reopenEpoch()));
    }

    private static Set<Integer> set(Integer... values) {
        return new HashSet<>(Arrays.asList(values));
    }
    private static final class Fixture {
        final Runtime runtime = new Runtime();
        final Schedule scheduler = new Schedule();
        final Store store = new Store();
        final TrustedGmsCloudMessagingSupervisor supervisor =
                new TrustedGmsCloudMessagingSupervisor(runtime, scheduler, store);
    }
    private static final class Store implements TrustedGmsCloudMessagingSupervisor.DesiredUserStore {
        Set<Integer> users = set(7);
        public Set<Integer> load() { return new HashSet<>(users); }
        public boolean save(Set<Integer> next) { users = new HashSet<>(next); return true; }
    }
    private static final class Runtime implements TrustedGmsCloudMessagingSupervisor.RuntimeOperations {
        int starts, stops;
        boolean accept = true;
        Runnable onInstalledProbe, onStart;
        final List<Integer> startedUsers = new ArrayList<>();
        public boolean isInstalled(int userId) {
            Runnable hook = onInstalledProbe;
            onInstalledProbe = null;
            if (hook != null) hook.run();
            return userId == 7 || userId == 9;
        }
        public int[] installedUserIds() { return new int[]{7, 9}; }
        public boolean startCloudMessaging(int userId) {
            starts++;
            startedUsers.add(userId);
            Runnable hook = onStart;
            onStart = null;
            if (hook != null) hook.run();
            return accept;
        }
        public void stopCloudMessaging(int userId) { stops++; }
        public boolean isPersistentProcessAlive(int userId) { return false; }
        public boolean isPersistentBindingAlive(int userId) { return false; }
    }
    private static final class Schedule implements TrustedGmsCloudMessagingSupervisor.Scheduler {
        final List<Runnable> callbacks = new ArrayList<>();
        public TrustedGmsCloudMessagingSupervisor.Cancellable schedule(Runnable task, long delay) {
            callbacks.add(task);
            return () -> { }; // Deliberately execute stale callbacks to test their own fences.
        }
        public long currentTimeMillis() { return 1000L; }
        void runCapturedIncludingCancelled() {
            List<Runnable> captured = new ArrayList<>(callbacks);
            callbacks.clear();
            for (Runnable task : captured) task.run();
        }
    }
}
