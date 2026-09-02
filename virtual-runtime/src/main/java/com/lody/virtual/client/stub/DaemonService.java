package com.lody.virtual.client.stub;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;

import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.server.am.DaemonWorkloadSnapshot;
import com.lody.virtual.server.am.VActivityManagerService;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Foreground lifetime anchor for user-requested cloned-app background work.
 *
 * <p>The service is started from an authorized user-visible flow or a bounded OS recovery entry.
 * Once started, it remains in the foreground while the virtual runtime reports active work. A
 * bounded idle grace prevents a transient process/task hand-off from tearing down the runtime,
 * while still removing the notification after the final workload finishes.</p>
 */
public class DaemonService extends Service {

    private static final int NOTIFY_ID = 1001;
    private static final String NOTIFICATION_CHANNEL_ID = "virtual_runtime_daemon";
    private static final String EXTRA_DESIRED_GMS_USER_IDS =
            "_VA_|_daemon_desired_gms_user_ids_";
    private static final String EXTRA_LAUNCH_AUTHORIZATION_TOKEN =
            "_VA_|_daemon_launch_authorization_token_";
    private static final String EXTRA_LINE_PUSH_RECOVERY_NONCE =
            "_VA_|_daemon_line_push_recovery_nonce_";
    private static final int MAX_PENDING_LINE_RECOVERY_NONCES = 64;
    private static final String LAST_VISIBLE_START_FILE = "virtual_runtime_daemon_visible_start";
    private static final String RECOVERY_SUPPRESSION_FILE =
            "virtual_runtime_daemon_recovery_suppression";
    private static final String TASK_REMOVAL_STOP_FILE =
            "virtual_runtime_daemon_task_removal_stop";
    private static final int RECOVERY_SUPPRESSION_MAGIC = 0x41545457;
    private static final int TASK_REMOVAL_STOP_MAGIC = 0x41545452;
    private static final long TASK_REMOVAL_EXIT_WINDOW_MS = 2_000L;
    private static final long PACKAGE_UPDATE_EXIT_WINDOW_MS = 10_000L;
    private static final long IDLE_GRACE_MS = 5_000L;
    private static final long WORKLOAD_POLL_MS = 5_000L;

    private static volatile boolean foregroundSessionActive;
    private static final SecureRandom lineRecoveryNonceRandom = new SecureRandom();
    private static final Set<Long> pendingLineRecoveryNonces = new HashSet<>();

    private enum LinePushRecoveryAuthorization {
        NONE,
        AUTHORIZED,
        INVALID
    }

    private static final class LinePushRecoveryStart {
        final LinePushRecoveryAuthorization authorization;
        final long nonce;

        LinePushRecoveryStart(LinePushRecoveryAuthorization authorization, long nonce) {
            this.authorization = authorization;
            this.nonce = nonce;
        }
    }

    private Handler handler;
    private DaemonLifetimePolicy lifetimePolicy;
    private int latestStartId;
    private boolean foreground;
    private boolean destroyed;
    private boolean preexistingForegroundSessionOnCreate;
    private boolean acceptedStartSeen;
    private int foregroundGmsUserCount = -1;

    private final Runnable workloadMonitor = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }

            VActivityManagerService activityManager = VActivityManagerService.get();
            DaemonWorkloadSnapshot snapshot = activityManager == null
                    ? null : activityManager.getDaemonWorkloadSnapshot();
            // An incomplete observation is not proof of idleness. Keep the FGS until the runtime
            // has completed initialization and can produce a reliable workload snapshot.
            boolean hasWorkload = snapshot == null
                    || !snapshot.isObservationReliable()
                    || snapshot.hasWorkload();
            if (snapshot != null && snapshot.isObservationReliable()) {
                updateForegroundNotification(snapshot.getGmsDesiredUserCount());
            }
            DaemonLifetimePolicy.Decision decision = lifetimePolicy.evaluate(
                    hasWorkload, SystemClock.elapsedRealtime());
            if (decision == DaemonLifetimePolicy.Decision.STOP) {
                DaemonWorkloadSnapshot confirmation = activityManager == null
                        ? null : activityManager.getDaemonWorkloadSnapshot();
                boolean confirmationReliable = confirmation != null
                        && confirmation.isObservationReliable();
                boolean confirmationHasWorkload = confirmation == null
                        || confirmation.hasWorkload();
                if (!DaemonLifetimePolicy.shouldStopAfterConfirmation(
                        confirmationReliable, confirmationHasWorkload)) {
                    // A workload appeared between the first observation and stop decision, or the
                    // second observation became unreliable. Reset the idle window and continue.
                    lifetimePolicy.onStart();
                    handler.postDelayed(this, WORKLOAD_POLL_MS);
                    return;
                }

                int evaluatedStartId = latestStartId;
                boolean stoppedAtomically = activityManager != null
                        && activityManager.runIfDaemonWorkloadStillIdle(
                        confirmation.getWorkloadGeneration(),
                        () -> stopSelfResult(evaluatedStartId));
                if (stoppedAtomically) {
                    DaemonJobService.cancelJob(DaemonService.this);
                    removeForeground();
                    return;
                }

                // A newer start won the race. Give that request a complete grace window and keep
                // monitoring instead of leaving an unmonitored foreground service behind.
                lifetimePolicy.onStart();
            }
            handler.postDelayed(this, WORKLOAD_POLL_MS);
        }
    };

    /**
     * Requests the daemon from a user-visible app flow where starting an FGS is permitted.
     * Repeated requests are safe and reset the pending idle-grace countdown.
     */
    public static void startup(Context context) {
        startup(context, null);
    }

    /** Visible startup carrying the host repository's authoritative enabled-user allowlist. */
    public static void startup(Context context, int[] desiredGmsUserIds) {
        startup(context, desiredGmsUserIds, null);
    }

    /** Visible startup whose completed exact reconciliation may be reused by a guest launch. */
    public static void startup(Context context, int[] desiredGmsUserIds,
            String launchAuthorizationToken) {
        Context appContext = context.getApplicationContext();
        long visibleStartAt = System.currentTimeMillis();
        recordVisibleStart(appContext, visibleStartAt);
        // Clear only after publishing the newer visible timestamp. A concurrent recovery job can
        // then either observe the old suppression or the new timestamp, never a cleared marker
        // paired with the stale timestamp that originally caused suppression.
        writeRecoverySuppression(appContext, false, visibleStartAt);
        startForegroundDaemon(
                appContext, desiredGmsUserIds, launchAuthorizationToken, 0L);
    }

    /**
     * Starts a previously authorized workload from an OS background-recovery entry point.
     * Unlike {@link #startup(Context)}, this never records visibility or clears user-stop state.
     */
    public static boolean startupForBackgroundRecovery(Context context) {
        return startupForBackgroundRecovery(context, null);
    }

    /** Background recovery carrying an authoritative allowlist without claiming visibility. */
    public static boolean startupForBackgroundRecovery(Context context,
                                                       int[] desiredGmsUserIds) {
        Context appContext = context.getApplicationContext();
        if (!allowsAutomaticRecovery(appContext)) {
            return false;
        }
        startForegroundDaemon(appContext, desiredGmsUserIds, null, 0L);
        return true;
    }

    /**
     * Reopens only the ordinary VAMS gate for an authenticated LINE push. This mode deliberately
     * does not reconcile or replace trusted-GMS supervisor state.
     */
    public static long prepareLinePushRecovery(Context context) {
        Context appContext = context.getApplicationContext();
        if (!allowsAutomaticRecovery(appContext)) {
            return 0L;
        }
        return authorizeLinePushRecovery();
    }

    /** Enqueues a nonce only after VAMS has installed its matching stop-epoch authorization. */
    public static synchronized void startPreparedLinePushRecovery(
            Context context, long nonce) {
        if (nonce == 0L || !pendingLineRecoveryNonces.contains(nonce)) {
            throw new IllegalStateException("LINE push recovery authorization is not pending");
        }
        startForegroundDaemon(context.getApplicationContext(), null, null, nonce);
    }

    /** Revokes a LINE-only start Intent that has not yet reached onStartCommand(). */
    public static synchronized void revokeLinePushRecovery(long nonce) {
        pendingLineRecoveryNonces.remove(nonce);
    }

    private static void startForegroundDaemon(Context appContext, int[] desiredGmsUserIds,
            String launchAuthorizationToken, long linePushRecoveryNonce) {
        Intent intent = new Intent(appContext, DaemonService.class);
        if (desiredGmsUserIds != null) {
            intent.putExtra(EXTRA_DESIRED_GMS_USER_IDS, desiredGmsUserIds.clone());
        }
        if (launchAuthorizationToken != null) {
            intent.putExtra(EXTRA_LAUNCH_AUTHORIZATION_TOKEN, launchAuthorizationToken);
        }
        if (linePushRecoveryNonce != 0L) {
            intent.putExtra(EXTRA_LINE_PUSH_RECOVERY_NONCE, linePushRecoveryNonce);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    /**
     * Allows bounded automatic recovery unless the engine was stopped by an explicit user action
     * after the most recent visible start. Reads fail open so old devices and unavailable exit
     * history preserve the trusted-GMS recovery contract.
     */
    public static boolean allowsAutomaticRecovery(Context context) {
        Context appContext = context.getApplicationContext();
        long lastVisibleStartAt = readVisibleStart(appContext);
        DaemonRecoveryPolicy.Suppression suppression = readRecoverySuppression(
                appContext, lastVisibleStartAt);
        if (suppression != DaemonRecoveryPolicy.Suppression.CLEAR) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }

        try {
            ServiceInfo daemonInfo = appContext.getPackageManager().getServiceInfo(
                    new ComponentName(appContext, DaemonService.class), 0);
            String engineProcessName = daemonInfo.processName;
            long packageLastUpdateAt = 0L;
            try {
                PackageInfo packageInfo = appContext.getPackageManager().getPackageInfo(
                        appContext.getPackageName(), 0);
                packageLastUpdateAt = packageInfo.lastUpdateTime;
            } catch (Throwable ignored) {
                // Missing update metadata is not evidence that a USER_REQUESTED exit was an APK
                // replacement. The explicit-stop policy remains fail-closed for that exit.
            }
            ActivityManager activityManager = (ActivityManager) appContext.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (activityManager == null || engineProcessName == null) {
                return true;
            }

            // maxNum=0 is intentional: Android returns all retained records. A busy clone can
            // easily place the private :x engine beyond the first 50 package process exits.
            List<ApplicationExitInfo> exits = activityManager.getHistoricalProcessExitReasons(
                    appContext.getPackageName(), 0, 0);
            java.util.ArrayList<DaemonRecoveryPolicy.ExitObservation> observations =
                    new java.util.ArrayList<>(exits.size());
            for (ApplicationExitInfo exit : exits) {
                if (exit != null) {
                    observations.add(new DaemonRecoveryPolicy.ExitObservation(
                            exit.getProcessName(), exit.getPid(), exit.getReason(),
                            exit.getTimestamp()));
                }
            }
            DaemonRecoveryPolicy.ExitObservation latestEngineExit =
                    DaemonRecoveryPolicy.latestForProcess(observations, engineProcessName);
            if (latestEngineExit == null) {
                return true;
            }
            DaemonRecoveryPolicy.TaskRemovalStop taskRemovalStop =
                    readTaskRemovalStop(appContext);
            DaemonRecoveryPolicy.Decision decision = DaemonRecoveryPolicy.evaluate(
                    suppression,
                    latestEngineExit,
                    lastVisibleStartAt,
                    taskRemovalStop,
                    ApplicationExitInfo.REASON_USER_REQUESTED,
                    TASK_REMOVAL_EXIT_WINDOW_MS,
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU,
                    packageLastUpdateAt,
                    PACKAGE_UPDATE_EXIT_WINDOW_MS);
            if (decision == DaemonRecoveryPolicy.Decision.PERSIST_DENIAL) {
                writeRecoverySuppression(appContext, true, latestEngineExit.timestamp);
                return false;
            }
            return decision == DaemonRecoveryPolicy.Decision.ALLOW;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** True only while this engine process owns an actively promoted daemon FGS. */
    public static boolean isForegroundSessionActive() {
        return foregroundSessionActive;
    }

    private static void writeRecoverySuppression(Context context, boolean suppressed,
                                                 long stateTimestamp) {
        AtomicFile marker = recoverySuppressionFile(context);
        FileOutputStream output = null;
        try {
            output = marker.startWrite();
            DataOutputStream data = new DataOutputStream(output);
            data.writeInt(RECOVERY_SUPPRESSION_MAGIC);
            data.writeByte(suppressed ? 1 : 0);
            data.writeLong(stateTimestamp);
            data.flush();
            marker.finishWrite(output);
        } catch (Throwable ignored) {
            if (output != null) {
                marker.failWrite(output);
            }
        }
    }

    private static DaemonRecoveryPolicy.Suppression readRecoverySuppression(
            Context context, long lastVisibleStartAt) {
        AtomicFile marker = recoverySuppressionFile(context);
        boolean baseFileExisted = marker.getBaseFile().exists();
        try (DataInputStream input = new DataInputStream(marker.openRead())) {
            if (input.readInt() != RECOVERY_SUPPRESSION_MAGIC) {
                return DaemonRecoveryPolicy.Suppression.CORRUPT;
            }
            int state = input.readUnsignedByte();
            long stateTimestamp = input.readLong();
            if ((state != 0 && state != 1) || input.read() != -1) {
                return DaemonRecoveryPolicy.Suppression.CORRUPT;
            }
            DaemonRecoveryPolicy.Suppression stored = state == 1
                    ? DaemonRecoveryPolicy.Suppression.SUPPRESSED
                    : DaemonRecoveryPolicy.Suppression.CLEAR;
            return DaemonRecoveryPolicy.resolveSuppression(
                    stored, stateTimestamp, lastVisibleStartAt);
        } catch (FileNotFoundException missing) {
            return baseFileExisted
                    ? DaemonRecoveryPolicy.Suppression.CORRUPT
                    : DaemonRecoveryPolicy.Suppression.CLEAR;
        } catch (Throwable ignored) {
            return DaemonRecoveryPolicy.Suppression.CORRUPT;
        }
    }

    private static void recordVisibleStart(Context context, long timestamp) {
        AtomicFile marker = visibleStartFile(context);
        FileOutputStream output = null;
        try {
            output = marker.startWrite();
            DataOutputStream data = new DataOutputStream(output);
            data.writeLong(timestamp);
            data.flush();
            marker.finishWrite(output);
        } catch (Throwable ignored) {
            if (output != null) {
                marker.failWrite(output);
            }
        }
    }

    private static long readVisibleStart(Context context) {
        AtomicFile marker = visibleStartFile(context);
        if (!marker.getBaseFile().exists()) {
            return 0L;
        }
        try (DataInputStream input = new DataInputStream(marker.openRead())) {
            return input.readLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static AtomicFile visibleStartFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), LAST_VISIBLE_START_FILE));
    }

    private static AtomicFile recoverySuppressionFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), RECOVERY_SUPPRESSION_FILE));
    }

    private static void recordTaskRemovalStop(Context context, int enginePid, long timestamp) {
        AtomicFile marker = taskRemovalStopFile(context);
        FileOutputStream output = null;
        try {
            output = marker.startWrite();
            DataOutputStream data = new DataOutputStream(output);
            data.writeInt(TASK_REMOVAL_STOP_MAGIC);
            data.writeInt(enginePid);
            data.writeLong(timestamp);
            data.flush();
            marker.finishWrite(output);
        } catch (Throwable ignored) {
            if (output != null) {
                marker.failWrite(output);
            }
        }
    }

    private static DaemonRecoveryPolicy.TaskRemovalStop readTaskRemovalStop(Context context) {
        AtomicFile marker = taskRemovalStopFile(context);
        if (!marker.getBaseFile().exists()) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(marker.openRead())) {
            if (input.readInt() != TASK_REMOVAL_STOP_MAGIC) {
                return null;
            }
            int enginePid = input.readInt();
            long timestamp = input.readLong();
            if (enginePid <= 0 || timestamp <= 0L || input.read() != -1) {
                return null;
            }
            return new DaemonRecoveryPolicy.TaskRemovalStop(enginePid, timestamp);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AtomicFile taskRemovalStopFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), TASK_REMOVAL_STOP_FILE));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        lifetimePolicy = new DaemonLifetimePolicy(IDLE_GRACE_MS);
        preexistingForegroundSessionOnCreate = foregroundSessionActive;
        ensureForeground(0);
    }

    private void ensureForeground(int gmsUserCount) {
        if (foreground) {
            return;
        }

        startForegroundNotification(gmsUserCount);
        foreground = true;
    }

    private void updateForegroundNotification(int gmsUserCount) {
        if (!foreground || foregroundGmsUserCount == gmsUserCount) {
            return;
        }
        startForegroundNotification(gmsUserCount);
    }

    private void startForegroundNotification(int gmsUserCount) {
        Notification notification = createForegroundNotification(gmsUserCount);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
        foregroundSessionActive = true;
        foregroundGmsUserCount = gmsUserCount;
    }

    private Notification createForegroundNotification(int gmsUserCount) {
        CharSequence applicationLabel = getApplicationInfo().loadLabel(getPackageManager());
        if (applicationLabel == null || applicationLabel.length() == 0) {
            applicationLabel = getPackageName();
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    applicationLabel + " 背景活動",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("維持已啟用空間的分身背景通知與服務");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_LOW);
        }

        Intent manageIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (manageIntent != null) {
            manageIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            builder.setContentIntent(PendingIntent.getActivity(
                    this, 0, manageIntent, pendingIntentFlags));
        }

        String title = applicationLabel + " 背景服務運作中";
        String description = "正在維持分身的背景活動；點一下管理";
        if (gmsUserCount > 0) {
            title += " · " + gmsUserCount + " 個空間";
            description = "正在維持背景通知；點一下管理各空間設定";
        }

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(description)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LinePushRecoveryStart linePushStart =
                consumeLinePushRecoveryAuthorization(intent);
        LinePushRecoveryAuthorization linePushRecovery = linePushStart.authorization;
        boolean automaticRecoveryAllowed = allowsAutomaticRecovery(this);
        VActivityManagerService activityManager = VActivityManagerService.get();
        if (linePushRecovery == LinePushRecoveryAuthorization.AUTHORIZED) {
            boolean authorizedReopen = automaticRecoveryAllowed
                    && activityManager != null
                    && activityManager.authorizeLinePushDaemonReopen(linePushStart.nonce);
            if (!authorizedReopen) {
                if (activityManager != null) {
                    activityManager.discardLinePushDaemonAuthorization(linePushStart.nonce);
                }
                linePushRecovery = LinePushRecoveryAuthorization.INVALID;
            }
        }

        // Reject a stale/revoked marker before it can mutate a legitimate daemon session. A fresh
        // FGS start still needs a brief promotion before it can safely stop itself, but an already
        // active foreground session is left entirely untouched.
        if (linePushRecovery == LinePushRecoveryAuthorization.INVALID) {
            if (acceptedStartSeen || preexistingForegroundSessionOnCreate) {
                // Android assigned this later startId even though its authorization was revoked.
                // Preserve normal idle-stop semantics without resetting the existing session.
                latestStartId = startId;
                return START_STICKY;
            }
            if (stopSelfResult(startId)) {
                removeForeground();
            }
            return START_NOT_STICKY;
        }

        ensureForeground(0);
        latestStartId = startId;
        lifetimePolicy.onStart();
        handler.removeCallbacks(workloadMonitor);

        // A system-created sticky restart has no user-visible startup() call to clear a durable
        // stop marker. Detect that case after promotion (to satisfy the FGS deadline), then tear
        // down without touching guest MCS.
        if (!automaticRecoveryAllowed) {
            DaemonJobService.cancelJob(this);
            if (stopSelfResult(startId)) {
                removeForeground();
            }
            return START_NOT_STICKY;
        }
        acceptedStartSeen = true;

        // The service has now been promoted to an FGS and the durable user-stop gate allowed
        // this start. Reopen VAMS workload acquisition before reconciliation. A successful idle
        // stop commits the complementary closed state under the same VAMS workload lock, so no
        // background Binder call can create new work in the stopped/no-FGS interval.
        if (activityManager != null
                && linePushRecovery != LinePushRecoveryAuthorization.AUTHORIZED) {
            activityManager.reopenDaemonWorkloadGate();
        }

        try {
            int[] desiredGmsUserIds = intent == null
                    ? null : intent.getIntArrayExtra(EXTRA_DESIRED_GMS_USER_IDS);
            String launchAuthorizationToken = intent == null
                    ? null : intent.getStringExtra(EXTRA_LAUNCH_AUTHORIZATION_TOKEN);
            boolean exactReconciliationAccepted = false;
            if (linePushRecovery == LinePushRecoveryAuthorization.AUTHORIZED) {
                // The authenticated wrapper already proved an existing durable GMS scope. Do not
                // let a LINE delivery perturb MCS retry/timeout state or exact desired-user data.
            } else if (desiredGmsUserIds == null) {
                VActivityManager.get().reconcileTrustedGmsCloudMessaging();
            } else {
                exactReconciliationAccepted =
                        VActivityManager.get().reconcileTrustedGmsCloudMessagingForUsers(
                        desiredGmsUserIds.clone());
            }
            if (exactReconciliationAccepted && activityManager != null
                    && launchAuthorizationToken != null) {
                activityManager.recordDaemonLaunchAuthorization(
                        launchAuthorizationToken, desiredGmsUserIds.clone());
            }
        } catch (Throwable ignored) {
            // The engine may still be initializing. Its active-session fallback and persisted
            // maintenance job provide independent, bounded recovery attempts.
        }

        handler.post(workloadMonitor);
        return START_STICKY;
    }

    private static synchronized long authorizeLinePushRecovery() {
        if (pendingLineRecoveryNonces.size() >= MAX_PENDING_LINE_RECOVERY_NONCES) return 0L;
        long nonce;
        do {
            nonce = lineRecoveryNonceRandom.nextLong();
        } while (nonce == 0L || pendingLineRecoveryNonces.contains(nonce));
        pendingLineRecoveryNonces.add(nonce);
        return nonce;
    }

    private static synchronized LinePushRecoveryStart
            consumeLinePushRecoveryAuthorization(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_LINE_PUSH_RECOVERY_NONCE)) {
            return new LinePushRecoveryStart(LinePushRecoveryAuthorization.NONE, 0L);
        }
        final long nonce;
        try {
            nonce = intent.getLongExtra(EXTRA_LINE_PUSH_RECOVERY_NONCE, 0L);
        } catch (RuntimeException malformedNonce) {
            return new LinePushRecoveryStart(LinePushRecoveryAuthorization.INVALID, 0L);
        }
        boolean authorized = nonce != 0L && pendingLineRecoveryNonces.remove(nonce);
        return new LinePushRecoveryStart(authorized
                ? LinePushRecoveryAuthorization.AUTHORIZED
                : LinePushRecoveryAuthorization.INVALID, nonce);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        int removedTaskStartId = latestStartId;
        VActivityManagerService activityManager = VActivityManagerService.get();
        DaemonWorkloadSnapshot snapshot = activityManager == null
                ? null : activityManager.getDaemonWorkloadSnapshot();
        boolean firstObservationIdle = snapshot != null
                && snapshot.isObservationReliable()
                && !snapshot.hasWorkload();
        if (firstObservationIdle) {
            DaemonWorkloadSnapshot confirmation = activityManager.getDaemonWorkloadSnapshot();
            boolean confirmedIdle = confirmation != null
                    && confirmation.isObservationReliable()
                    && !confirmation.hasWorkload();
            boolean stoppedAtomically = confirmedIdle
                    && activityManager.runIfDaemonWorkloadStillIdle(
                            confirmation.getWorkloadGeneration(), () -> {
                                if (!stopSelfResult(removedTaskStartId)) {
                                    return false;
                                }
                                // Persist only after the captured startId was accepted, and while
                                // VAMS excludes workload mutations under its generation guard.
                                recordTaskRemovalStop(this, android.os.Process.myPid(),
                                        System.currentTimeMillis());
                                return true;
                            });
            if (stoppedAtomically) {
                DaemonJobService.cancelJob(this);
                removeForeground();
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    private void removeForeground() {
        foregroundSessionActive = false;
        if (foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
        }
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFY_ID);
        }
        foreground = false;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (handler != null) {
            handler.removeCallbacks(workloadMonitor);
        }
        removeForeground();
        super.onDestroy();
    }
}

/** Pure idle-grace policy kept separate from Android lifecycle code for unit testing. */
final class DaemonLifetimePolicy {
    enum Decision { KEEP, WAIT, STOP }

    private final long idleGraceMs;
    private long idleSinceMs = -1L;

    DaemonLifetimePolicy(long idleGraceMs) {
        if (idleGraceMs < 0L) {
            throw new IllegalArgumentException("idleGraceMs must be non-negative");
        }
        this.idleGraceMs = idleGraceMs;
    }

    void onStart() {
        idleSinceMs = -1L;
    }

    Decision evaluate(boolean hasWorkload, long nowMs) {
        if (hasWorkload) {
            idleSinceMs = -1L;
            return Decision.KEEP;
        }
        if (idleSinceMs < 0L) {
            idleSinceMs = nowMs;
            return idleGraceMs == 0L ? Decision.STOP : Decision.WAIT;
        }
        return nowMs - idleSinceMs >= idleGraceMs ? Decision.STOP : Decision.WAIT;
    }

    static boolean shouldStopAfterConfirmation(boolean observationReliable,
                                               boolean hasWorkload) {
        return observationReliable && !hasWorkload;
    }
}

/** Pure exit-reason/timestamp gate used by the JobService and delayed engine reconciliation. */
final class DaemonRecoveryPolicy {
    enum Suppression { CLEAR, SUPPRESSED, CORRUPT }
    enum Decision { ALLOW, DENY, PERSIST_DENIAL }

    static final class ExitObservation {
        final String processName;
        final int pid;
        final int reason;
        final long timestamp;

        ExitObservation(String processName, int pid, int reason, long timestamp) {
            this.processName = processName;
            this.pid = pid;
            this.reason = reason;
            this.timestamp = timestamp;
        }
    }

    static final class TaskRemovalStop {
        final int enginePid;
        final long timestamp;

        TaskRemovalStop(int enginePid, long timestamp) {
            this.enginePid = enginePid;
            this.timestamp = timestamp;
        }
    }

    private DaemonRecoveryPolicy() {
    }

    static Decision evaluate(Suppression suppression, ExitObservation latestExit,
                             long lastVisibleStartAt, TaskRemovalStop taskRemovalStop,
                             int userRequestedReason, long taskRemovalWindowMs,
                             boolean userRequestedMayMeanPackageUpdate,
                             long packageLastUpdateAt, long packageUpdateWindowMs) {
        if (suppression != Suppression.CLEAR) {
            return Decision.DENY;
        }
        if (latestExit == null || latestExit.reason != userRequestedReason
                || latestExit.timestamp <= lastVisibleStartAt) {
            return Decision.ALLOW;
        }
        if (matchesTaskRemovalStop(latestExit, taskRemovalStop, taskRemovalWindowMs)) {
            return Decision.ALLOW;
        }
        if (userRequestedMayMeanPackageUpdate
                && matchesPackageUpdate(latestExit.timestamp, packageLastUpdateAt,
                packageUpdateWindowMs)) {
            return Decision.ALLOW;
        }
        if (latestExit.timestamp > lastVisibleStartAt) {
            return Decision.PERSIST_DENIAL;
        }
        return Decision.ALLOW;
    }

    static ExitObservation latestForProcess(java.util.List<ExitObservation> exits,
                                            String processName) {
        if (exits == null || processName == null) {
            return null;
        }
        ExitObservation latest = null;
        for (ExitObservation exit : exits) {
            if (exit == null || !processName.equals(exit.processName)) {
                continue;
            }
            if (latest == null || exit.timestamp > latest.timestamp) {
                latest = exit;
            }
        }
        return latest;
    }

    static boolean matchesTaskRemovalStop(ExitObservation exit, TaskRemovalStop taskRemovalStop,
                                          long taskRemovalWindowMs) {
        if (exit == null || taskRemovalStop == null || taskRemovalWindowMs < 0L
                || exit.pid != taskRemovalStop.enginePid
                || exit.timestamp < taskRemovalStop.timestamp) {
            return false;
        }
        long latestMatch = taskRemovalStop.timestamp > Long.MAX_VALUE - taskRemovalWindowMs
                ? Long.MAX_VALUE : taskRemovalStop.timestamp + taskRemovalWindowMs;
        return exit.timestamp <= latestMatch;
    }

    static boolean matchesPackageUpdate(long exitAt, long packageLastUpdateAt,
                                        long packageUpdateWindowMs) {
        if (exitAt <= 0L || packageLastUpdateAt <= 0L || packageUpdateWindowMs < 0L
                || exitAt > packageLastUpdateAt) {
            return false;
        }
        return packageLastUpdateAt - exitAt <= packageUpdateWindowMs;
    }

    static Suppression resolveSuppression(Suppression stored, long suppressionAt,
                                          long lastVisibleStartAt) {
        if (stored == Suppression.CORRUPT) {
            return Suppression.CORRUPT;
        }
        if (stored == Suppression.SUPPRESSED && suppressionAt > lastVisibleStartAt) {
            return Suppression.SUPPRESSED;
        }
        return Suppression.CLEAR;
    }
}
