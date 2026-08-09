package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class TrustedGmsSuspensionCoordinatorTest {

    @Test
    public void everyPreCommitFailureIsRepairableByRestartRetry() {
        for (int failureStep = 1; failureStep <= 5; failureStep++) {
            FakeOperations firstProcess = new FakeOperations(failureStep);

            assertFalse(TrustedGmsSuspensionCoordinator.suspend(firstProcess));
            if (failureStep < 5) {
                assertTrue("unbind must not precede complete cleanup", firstProcess.installed);
            }

            FakeOperations restarted = firstProcess.restartWithoutFailure();
            assertTrue(TrustedGmsSuspensionCoordinator.suspend(restarted));
            assertFalse(restarted.installed);
            assertTrue(restarted.backgroundOwners.isEmpty());
            assertTrue("retry must replay jobs cleanup", restarted.jobsClearCalls > 0);
            assertTrue("retry must replay notification cleanup", restarted.notificationClearCalls > 0);
            assertTrue("retry must replay pending-intent cleanup", restarted.pendingClearCalls > 0);
        }
    }

    @Test
    public void verificationFailureAfterDurableUnbindStillReplaysAllCleanupOnRetry() {
        FakeOperations firstProcess = new FakeOperations(6);

        assertFalse(TrustedGmsSuspensionCoordinator.suspend(firstProcess));
        assertFalse("unbind was durably committed", firstProcess.installed);

        // Model state loaded by a fresh server process. No receipt exists, and durable package
        // state alone must not short-circuit the idempotent cleanup sequence.
        firstProcess.backgroundOwners.add("notification-restored-from-disk");
        FakeOperations restarted = firstProcess.restartWithoutFailure();
        assertTrue(TrustedGmsSuspensionCoordinator.suspend(restarted));
        assertFalse(restarted.installed);
        assertTrue(restarted.backgroundOwners.isEmpty());
        assertTrue(restarted.jobsClearCalls > 0);
        assertTrue(restarted.notificationClearCalls > 0);
        assertTrue(restarted.pendingClearCalls > 0);
    }

    private static final class FakeOperations
            implements TrustedGmsSuspensionCoordinator.Operations {
        boolean installed = true;
        final Set<String> backgroundOwners = new HashSet<>();
        int failureStep;
        int currentStep;
        int jobsClearCalls;
        int notificationClearCalls;
        int pendingClearCalls;

        FakeOperations(int failureStep) {
            this.failureStep = failureStep;
            backgroundOwners.add("job");
            backgroundOwners.add("notification");
            backgroundOwners.add("pending-intent");
        }

        FakeOperations restartWithoutFailure() {
            FakeOperations restarted = new FakeOperations(0);
            restarted.installed = installed;
            restarted.backgroundOwners.clear();
            restarted.backgroundOwners.addAll(backgroundOwners);
            return restarted;
        }

        private void failAtStep() throws Exception {
            currentStep++;
            if (failureStep == currentStep) throw new Exception("injected");
        }

        @Override
        public void killProcesses() {
            currentStep++;
            if (failureStep == currentStep) throw new IllegalStateException("injected");
        }

        @Override
        public void clearJobs() throws Exception {
            jobsClearCalls++;
            failAtStep();
            backgroundOwners.remove("job");
        }

        @Override
        public void clearNotifications() throws Exception {
            notificationClearCalls++;
            failAtStep();
            backgroundOwners.removeIf(owner -> owner.startsWith("notification"));
        }

        @Override
        public void clearPendingIntents() throws Exception {
            pendingClearCalls++;
            failAtStep();
            backgroundOwners.remove("pending-intent");
        }

        @Override
        public boolean isInstalled() {
            return installed;
        }

        @Override
        public void commitUnbind() throws Exception {
            failAtStep();
            installed = false;
        }

        @Override
        public boolean hasBackgroundOwnership() {
            currentStep++;
            if (failureStep == currentStep) throw new IllegalStateException("injected");
            return !backgroundOwners.isEmpty();
        }
    }
}
