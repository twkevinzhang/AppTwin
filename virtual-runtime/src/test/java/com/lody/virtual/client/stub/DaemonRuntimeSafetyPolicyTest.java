package com.lody.virtual.client.stub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class DaemonRuntimeSafetyPolicyTest {
    @Test
    public void daemonDoesNotRestartItselfFromOnDestroy() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int onDestroy = source.indexOf("void onDestroy()");

        assertTrue(onDestroy >= 0);
        assertFalse(source.substring(onDestroy).contains("startup(this)"));
        assertTrue(source.contains("return START_STICKY;"));
        assertTrue(source.contains("return START_NOT_STICKY;"));
    }

    @Test
    public void daemonUsesTypedForegroundAndStartIdSafeIdleStop() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int onCreate = source.indexOf("void onCreate()");
        int onStart = source.indexOf("int onStartCommand(");

        assertTrue(onCreate >= 0);
        assertTrue(onStart > onCreate);
        assertTrue(source.substring(onCreate, onStart).contains("ensureForeground(0);"));
        assertTrue(source.substring(onStart).contains("ensureForeground(0);"));
        assertTrue(source.contains("FOREGROUND_SERVICE_TYPE_SPECIAL_USE"));
        assertTrue(source.contains("WORKLOAD_POLL_MS = 5_000L"));
        assertTrue(source.contains("stopSelfResult(evaluatedStartId)"));
        assertTrue(source.contains("shouldStopAfterConfirmation("));
        assertTrue(source.contains("lifetimePolicy.onStart();"));
        assertTrue(source.contains("handler.removeCallbacks(workloadMonitor)"));
        int recoveryGate = source.indexOf("if (!automaticRecoveryAllowed)", onStart);
        int deniedReturn = source.indexOf("return START_NOT_STICKY;", recoveryGate);
        int reopenGate = source.indexOf("reopenDaemonWorkloadGate()", recoveryGate);
        int reconciliation = source.indexOf("reconcileTrustedGmsCloudMessaging()", onStart);
        assertTrue(recoveryGate > onStart);
        assertTrue(deniedReturn > recoveryGate);
        assertTrue(reopenGate > deniedReturn);
        assertTrue(reconciliation > reopenGate);
    }

    @Test
    public void periodicIdleStopCommitsThroughAtomicWorkloadGeneration() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int monitor = source.indexOf("Runnable workloadMonitor");
        int startup = source.indexOf("public static void startup(Context context)");
        String callback = source.substring(monitor, startup);

        assertTrue(callback.contains("confirmation.getWorkloadGeneration()"));
        assertTrue(callback.contains("runIfDaemonWorkloadStillIdle("));
        assertTrue(callback.contains("() -> stopSelfResult(evaluatedStartId)"));
        assertTrue(callback.indexOf("stopSelfResult(evaluatedStartId)")
                > callback.indexOf("runIfDaemonWorkloadStillIdle("));
    }

    @Test
    public void notificationExplainsBackgroundWorkAndOpensManagementUi() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");

        assertTrue(source.contains("背景服務運作中"));
        assertTrue(source.contains("個空間"));
        assertTrue(source.contains("管理各空間設定"));
        assertTrue(source.contains("getLaunchIntentForPackage(getPackageName())"));
        assertTrue(source.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertTrue(source.contains("setContentIntent("));
    }

    @Test
    public void acceptedStartRepublishesOnlyWhenMarkedDaemonNotificationIsMissing()
            throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int ensureForeground = source.indexOf("private void ensureForeground(");
        int updateForeground = source.indexOf("private void updateForegroundNotification(");
        String ensureBody = source.substring(ensureForeground, updateForeground);

        assertTrue(ensureBody.contains("Build.VERSION_CODES.M"));
        assertTrue(ensureBody.contains("isDaemonForegroundNotificationActive()"));
        assertTrue(ensureBody.contains("DaemonForegroundNotificationPolicy.shouldPublish("));
        assertTrue(source.contains("notificationManager.getActiveNotifications()"));
        assertTrue(source.contains(
                "getPackageName().equals(activeNotification.getPackageName())"));
        assertTrue(source.contains("activeNotification.getId() != NOTIFY_ID"));
        assertTrue(source.contains("activeNotification.getTag() != null"));
        assertTrue(source.contains("Notification.FLAG_FOREGROUND_SERVICE"));
        assertTrue(source.contains("EXTRA_DAEMON_FOREGROUND_NOTIFICATION"));
        assertTrue(source.contains(".addExtras(extras)"));
    }

    @Test
    public void periodicJobNeverStartsForegroundDaemon() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonJobService.java");

        assertFalse(source.contains("DaemonService.startup"));
        assertFalse(source.contains("startForegroundService"));
        assertFalse(source.contains("startService("));
        assertTrue(source.contains("isForegroundSessionActive()"));
        assertTrue(source.contains("allowsAutomaticRecovery(this)"));
        assertFalse(source.contains("cancelJob(this)"));
        assertTrue(source.contains("reconcileTrustedGmsCloudMessaging"));
        assertTrue(source.indexOf("allowsAutomaticRecovery(this)")
                < source.indexOf("shouldRunRecovery(foregroundActive, recoveryAllowed)"));
    }

    @Test
    public void idleStopCancelsPeriodicRecoveryAndClearsForegroundSession() throws Exception {
        String daemon = readSource("com/lody/virtual/client/stub/DaemonService.java");
        String job = readSource("com/lody/virtual/client/stub/DaemonJobService.java");

        assertTrue(daemon.contains("DaemonJobService.cancelJob(DaemonService.this)"));
        assertTrue(daemon.contains("foregroundSessionActive = true"));
        assertTrue(daemon.contains("foregroundSessionActive = false"));
        assertTrue(job.contains("jobScheduler.cancel(JOB_ID)"));
        assertTrue(job.contains(".setPersisted(true)"));
    }

    @Test
    public void everyServiceDestroyExplicitlyRemovesTheForegroundNotification() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int onDestroy = source.indexOf("void onDestroy()");

        assertTrue(onDestroy >= 0);
        assertTrue(source.substring(onDestroy).contains("removeForeground();"));
        assertTrue(source.contains("notificationManager.cancel(NOTIFY_ID)"));
    }

    @Test
    public void taskRemovalStopsOnlyAfterTwoCompleteReliableIdleSnapshots() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int onTaskRemoved = source.indexOf("void onTaskRemoved(");
        int onDestroy = source.indexOf("void onDestroy()");

        assertTrue(onTaskRemoved >= 0);
        assertTrue(onDestroy > onTaskRemoved);
        String callback = source.substring(onTaskRemoved, onDestroy);
        assertTrue(callback.contains("snapshot.isObservationReliable()"));
        assertTrue(callback.contains("!snapshot.hasWorkload()"));
        assertTrue(callback.contains("confirmation.isObservationReliable()"));
        assertTrue(callback.contains("!confirmation.hasWorkload()"));
        assertTrue(callback.contains("confirmation.getWorkloadGeneration()"));
        assertTrue(callback.contains("runIfDaemonWorkloadStillIdle("));
        assertTrue(callback.contains("stopSelfResult(removedTaskStartId)"));
        assertTrue(callback.indexOf("recordTaskRemovalStop(this")
                > callback.indexOf("stopSelfResult(removedTaskStartId)"));
        assertTrue(callback.contains("DaemonJobService.cancelJob(this)"));
        assertTrue(callback.contains("removeForeground();"));
        assertFalse(callback.contains("stopSelf();"));
    }

    @Test
    public void exitHistoryScansAllRecordsWithoutLocalizedDescriptions() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");

        assertTrue(source.contains("getHistoricalProcessExitReasons("));
        assertTrue(source.contains("appContext.getPackageName(), 0, 0)"));
        assertFalse(source.contains("getDescription()"));
        assertFalse(source.contains("REASON_USER_STOPPED"));
    }

    @Test
    public void backgroundRecoveryEntryNeverClaimsVisibleStartupOrClearsSuppression()
            throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int recovery = source.indexOf(
                "public static boolean startupForBackgroundRecovery(Context context)");
        int helper = source.indexOf("private static void startForegroundDaemon", recovery);

        assertTrue(recovery >= 0);
        assertTrue(helper > recovery);
        String entry = source.substring(recovery, helper);
        assertTrue(entry.contains("allowsAutomaticRecovery(appContext)"));
        assertTrue(entry.contains(
                "startForegroundDaemon(appContext, desiredGmsUserIds, null, 0L)"));
        assertFalse(entry.contains("recordVisibleStart"));
        assertFalse(entry.contains("writeRecoverySuppression"));
    }

    @Test
    public void linePushRecoveryReopensForegroundGateWithoutReconcilingGms() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int entry = source.indexOf(
                "public static long prepareLinePushRecovery(Context context)");
        int helper = source.indexOf("private static void startForegroundDaemon", entry);
        String recoveryEntry = source.substring(entry, helper);
        assertTrue(recoveryEntry.contains("allowsAutomaticRecovery(appContext)"));
        assertTrue(recoveryEntry.contains("authorizeLinePushRecovery()"));
        assertTrue(recoveryEntry.contains("startPreparedLinePushRecovery("));
        assertTrue(recoveryEntry.contains(
                "startForegroundDaemon(context.getApplicationContext(), null, null, nonce)"));
        assertFalse(recoveryEntry.contains("recordVisibleStart"));
        assertFalse(recoveryEntry.contains("writeRecoverySuppression"));

        int onStart = source.indexOf("int onStartCommand(");
        int taskRemoved = source.indexOf("void onTaskRemoved(", onStart);
        String onStartBody = source.substring(onStart, taskRemoved);
        assertTrue(onStartBody.indexOf("consumeLinePushRecoveryAuthorization(intent)")
                < onStartBody.indexOf("allowsAutomaticRecovery(this)"));
        int modeCheck = onStartBody.indexOf(
                "linePushRecovery == LinePushRecoveryAuthorization.AUTHORIZED");
        int genericReconcile = onStartBody.indexOf(
                "reconcileTrustedGmsCloudMessaging();");
        int exactReconcile = onStartBody.indexOf(
                "reconcileTrustedGmsCloudMessagingForUsers(");
        assertTrue(onStartBody.contains("activityManager.reopenDaemonWorkloadGate();"));
        assertTrue(modeCheck >= 0);
        assertTrue(genericReconcile > modeCheck);
        assertTrue(exactReconcile > modeCheck);
        assertTrue(onStartBody.substring(modeCheck,
                onStartBody.indexOf("} else if", modeCheck))
                .contains("Do not"));
        assertTrue(source.contains("MAX_PENDING_LINE_RECOVERY_NONCES = 64"));
        assertTrue(source.contains("SecureRandom lineRecoveryNonceRandom"));
    }

    @Test
    public void freshUnknownLineRecoveryNonceStopsWithoutGenericReconciliation()
            throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int onStart = source.indexOf("int onStartCommand(");
        int taskRemoved = source.indexOf("void onTaskRemoved(", onStart);
        String onStartBody = source.substring(onStart, taskRemoved);

        int consume = onStartBody.indexOf("consumeLinePushRecoveryAuthorization(intent)");
        int invalid = onStartBody.indexOf(
                "linePushRecovery == LinePushRecoveryAuthorization.INVALID");
        int invalidReturn = onStartBody.indexOf("return START_NOT_STICKY", invalid);
        int gateReopen = onStartBody.indexOf("activityManager.reopenDaemonWorkloadGate()");
        int genericReconcile = onStartBody.indexOf(
                "reconcileTrustedGmsCloudMessaging();");
        assertTrue(consume >= 0);
        assertTrue(invalid > consume);
        assertTrue(invalidReturn > invalid);
        assertTrue(gateReopen > invalidReturn);
        assertTrue(genericReconcile > invalidReturn);
        String invalidBranch = onStartBody.substring(invalid, invalidReturn);
        assertTrue(invalidBranch.contains(
                "acceptedStartSeen || preexistingForegroundSessionOnCreate"));
        assertTrue(invalidBranch.contains("stopSelfResult(startId)"));
        assertTrue(invalidBranch.contains("removeForeground()"));
        assertTrue(source.contains("LinePushRecoveryAuthorization.NONE"));
        assertTrue(source.contains("LinePushRecoveryAuthorization.AUTHORIZED"));
        assertTrue(source.contains("LinePushRecoveryAuthorization.INVALID"));
        assertTrue(source.contains("intent.hasExtra(EXTRA_LINE_PUSH_RECOVERY_NONCE)"));
        assertTrue(source.contains("catch (RuntimeException malformedNonce)"));
    }

    @Test
    public void staleUnknownNonceDoesNotStopOrRemoveExistingAuthorizedForegroundSession()
            throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int onStart = source.indexOf("int onStartCommand(");
        int invalid = source.indexOf(
                "linePushRecovery == LinePushRecoveryAuthorization.INVALID", onStart);
        int freshStop = source.indexOf("if (stopSelfResult(startId))", invalid);
        String existingSessionBranch = source.substring(invalid, freshStop);

        assertTrue(existingSessionBranch.contains(
                "acceptedStartSeen || preexistingForegroundSessionOnCreate"));
        assertTrue(existingSessionBranch.contains("latestStartId = startId"));
        assertTrue(existingSessionBranch.contains("return START_STICKY"));
        assertFalse(existingSessionBranch.contains("stopSelfResult("));
        assertFalse(existingSessionBranch.contains("removeForeground("));
        assertTrue(source.indexOf("acceptedStartSeen = true", invalid) > invalid);
    }

    @Test
    public void authoritativeEnabledUserAllowlistCrossesTheServiceIntent() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");

        assertTrue(source.contains("startup(Context context, int[] desiredGmsUserIds)"));
        assertTrue(source.contains(
                "startupForBackgroundRecovery(Context context,"));
        assertTrue(source.contains("intent.putExtra(EXTRA_DESIRED_GMS_USER_IDS"));
        assertTrue(source.contains("getIntArrayExtra(EXTRA_DESIRED_GMS_USER_IDS)"));
        assertTrue(source.contains("reconcileTrustedGmsCloudMessagingForUsers("));
    }

    @Test
    public void visibleStartPublishesTimestampBeforeClearingStickySuppression() throws Exception {
        String source = readSource("com/lody/virtual/client/stub/DaemonService.java");
        int startup = source.indexOf("public static void startup(Context context)");
        int visibleTimestamp = source.indexOf("recordVisibleStart(", startup);
        int clearSuppression = source.indexOf("writeRecoverySuppression(appContext, false", startup);

        assertTrue(startup >= 0);
        assertTrue(visibleTimestamp > startup);
        assertTrue(clearSuppression > visibleTimestamp);
    }

    @Test
    public void providerAndExplicitProcessInitializationAreFailClosedAcquisitions()
            throws Exception {
        String source = readSource(
                "com/lody/virtual/server/am/VActivityManagerService.java");
        int acquireProvider = source.indexOf("IBinder acquireProviderClient(");
        int callingActivity = source.indexOf("ComponentName getCallingActivity(", acquireProvider);
        String providerPath = source.substring(acquireProvider, callingActivity);
        assertTrue(providerPath.contains("if (!beginDaemonWorkloadAcquisition()) return null;"));
        assertTrue(providerPath.contains("startProcessIfNeedLocked("));
        assertTrue(providerPath.contains("finally"));
        assertTrue(providerPath.contains("endDaemonWorkloadMutation();"));

        int initProcess = source.indexOf("int initProcess(");
        int ensureGms = source.indexOf(
                "boolean ensureTrustedGmsCloudMessagingForUser(", initProcess);
        String initPath = source.substring(initProcess, ensureGms);
        assertTrue(initPath.contains("if (!beginDaemonWorkloadAcquisition()) return -1;"));
        assertTrue(initPath.contains("startProcessIfNeedLocked("));
        assertTrue(initPath.contains("finally"));
        assertTrue(initPath.contains("endDaemonWorkloadMutation();"));
    }

    private static String readSource(String relativePath) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("src/main/java", relativePath));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
