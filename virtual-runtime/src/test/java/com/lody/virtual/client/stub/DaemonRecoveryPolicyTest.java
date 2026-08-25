package com.lody.virtual.client.stub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DaemonRecoveryPolicyTest {
    private static final int USER_REQUESTED = 10;
    private static final int USER_STOPPED = 11;
    private static final long REMOVE_TASK_WINDOW_MS = 2_000L;

    @Test
    public void activeAppsStopSuppressesWithoutDependingOnDescription() {
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluate(exit(501, USER_REQUESTED, 2_000L), 1_000L, null));
    }

    @Test
    public void nullAndNonEnglishDescriptionsAreNotPolicyInputs() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/lody/virtual/client/stub/DaemonService.java")),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("getDescription()"));
        assertFalse(source.contains("describesExplicitStop"));
        // A localized system description cannot alter a reason/PID/time decision because the
        // pure observation intentionally has no description field.
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluate(new DaemonRecoveryPolicy.ExitObservation(
                        "org.apptwin:x", 501, USER_REQUESTED, 2_000L), 1_000L, null));
    }

    @Test
    public void removeTaskSelfStopIsAllowedOnlyForSamePidAndNarrowWindow() {
        DaemonRecoveryPolicy.TaskRemovalStop marker =
                new DaemonRecoveryPolicy.TaskRemovalStop(501, 2_000L);

        assertEquals(DaemonRecoveryPolicy.Decision.ALLOW,
                evaluate(exit(501, USER_REQUESTED, 2_000L), 1_000L, marker));
        assertEquals(DaemonRecoveryPolicy.Decision.ALLOW,
                evaluate(exit(501, USER_REQUESTED, 4_000L), 1_000L, marker));
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluate(exit(501, USER_REQUESTED, 4_001L), 1_000L, marker));
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluate(exit(502, USER_REQUESTED, 2_001L), 1_000L, marker));
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluate(exit(501, USER_REQUESTED, 1_999L), 1_000L, marker));
    }

    @Test
    public void moreThanFiftyPackageRecordsStillFindLatestEngineExit() {
        List<DaemonRecoveryPolicy.ExitObservation> exits = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            exits.add(new DaemonRecoveryPolicy.ExitObservation(
                    "org.apptwin:p" + index, 1_000 + index, 3, 10_000L + index));
        }
        exits.add(175, exit(501, USER_REQUESTED, 30_000L));
        exits.add(exit(499, 3, 20_000L));

        DaemonRecoveryPolicy.ExitObservation latest =
                DaemonRecoveryPolicy.latestForProcess(exits, "org.apptwin:x");

        assertEquals(30_000L, latest.timestamp);
        assertEquals(501, latest.pid);
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluate(latest, 1_000L, null));
    }

    @Test
    public void userStoppedReasonIsNotTreatedAsExplicitAppStop() {
        assertEquals(DaemonRecoveryPolicy.Decision.ALLOW,
                evaluate(exit(501, USER_STOPPED, 2_000L), 1_000L, null));
    }

    @Test
    public void stickySuppressionSurvivesLaterLowMemoryExit() {
        assertEquals(DaemonRecoveryPolicy.Decision.DENY,
                DaemonRecoveryPolicy.evaluate(
                        DaemonRecoveryPolicy.Suppression.SUPPRESSED,
                        exit(501, 3, 3_000L),
                        1_000L,
                        null,
                        USER_REQUESTED,
                        REMOVE_TASK_WINDOW_MS,
                        false,
                        0L,
                        10_000L));
    }

    @Test
    public void visibleStartupAndCorruptMarkerPoliciesRemainFailSafe() {
        assertEquals(DaemonRecoveryPolicy.Decision.ALLOW,
                evaluate(exit(501, USER_REQUESTED, 1_000L), 2_000L, null));
        assertEquals(DaemonRecoveryPolicy.Decision.DENY,
                DaemonRecoveryPolicy.evaluate(
                        DaemonRecoveryPolicy.Suppression.CORRUPT,
                        exit(501, 3, Long.MAX_VALUE),
                        0L,
                        null,
                        USER_REQUESTED,
                        REMOVE_TASK_WINDOW_MS,
                        false,
                        0L,
                        10_000L));
    }

    @Test
    public void api30To33PackageUpdateExclusionIsDirectionalAndBounded() {
        DaemonRecoveryPolicy.ExitObservation beforeUpdate =
                exit(501, USER_REQUESTED, 20_000L);

        assertEquals(DaemonRecoveryPolicy.Decision.ALLOW,
                evaluateForUpdate(beforeUpdate, 30_000L, 10_000L, true));
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluateForUpdate(beforeUpdate, 30_001L, 10_000L, true));
        // An explicit stop immediately after the APK update is not the update-triggered exit.
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluateForUpdate(
                        exit(501, USER_REQUESTED, 30_001L), 30_000L, 10_000L, true));
        // API 34+ has a stable package-update reason and must not use this compatibility carveout.
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluateForUpdate(beforeUpdate, 30_000L, 10_000L, false));
    }

    @Test
    public void invalidPackageUpdateMetadataNeverMasksExplicitStop() {
        DaemonRecoveryPolicy.ExitObservation explicitStop =
                exit(501, USER_REQUESTED, 20_000L);

        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluateForUpdate(explicitStop, 0L, 10_000L, true));
        assertEquals(DaemonRecoveryPolicy.Decision.PERSIST_DENIAL,
                evaluateForUpdate(explicitStop, 20_000L, -1L, true));
    }

    @Test
    public void staleSuppressionWriteCannotOverrideNewVisibleTimestamp() {
        assertEquals(DaemonRecoveryPolicy.Suppression.CLEAR,
                DaemonRecoveryPolicy.resolveSuppression(
                        DaemonRecoveryPolicy.Suppression.SUPPRESSED, 2_000L, 3_000L));
        assertEquals(DaemonRecoveryPolicy.Suppression.SUPPRESSED,
                DaemonRecoveryPolicy.resolveSuppression(
                        DaemonRecoveryPolicy.Suppression.SUPPRESSED, 3_000L, 2_000L));
    }

    private static DaemonRecoveryPolicy.Decision evaluate(
            DaemonRecoveryPolicy.ExitObservation exit, long visibleAt,
            DaemonRecoveryPolicy.TaskRemovalStop taskRemovalStop) {
        return DaemonRecoveryPolicy.evaluate(
                DaemonRecoveryPolicy.Suppression.CLEAR,
                exit,
                visibleAt,
                taskRemovalStop,
                USER_REQUESTED,
                REMOVE_TASK_WINDOW_MS,
                false,
                0L,
                10_000L);
    }

    private static DaemonRecoveryPolicy.Decision evaluateForUpdate(
            DaemonRecoveryPolicy.ExitObservation exit, long packageLastUpdateAt,
            long packageUpdateWindowMs, boolean compatibilityApplies) {
        return DaemonRecoveryPolicy.evaluate(
                DaemonRecoveryPolicy.Suppression.CLEAR,
                exit,
                1_000L,
                null,
                USER_REQUESTED,
                REMOVE_TASK_WINDOW_MS,
                compatibilityApplies,
                packageLastUpdateAt,
                packageUpdateWindowMs);
    }

    private static DaemonRecoveryPolicy.ExitObservation exit(
            int pid, int reason, long timestamp) {
        return new DaemonRecoveryPolicy.ExitObservation(
                "org.apptwin:x", pid, reason, timestamp);
    }
}
