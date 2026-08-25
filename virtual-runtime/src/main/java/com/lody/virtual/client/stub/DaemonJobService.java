package com.lody.virtual.client.stub;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.server.pm.PrivilegeAppOptimizer;

import java.util.concurrent.TimeUnit;

/**
 * author: weishu on 2018/4/10.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class DaemonJobService extends JobService {

    static final int JOB_ID = 1;
    static final long PERIODIC_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15);

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean foregroundActive = DaemonService.isForegroundSessionActive();
        // Evaluate the durable gate even in a fresh process: this is what persists a newly
        // observed REASON_USER_REQUESTED before the inactive job cancels itself.
        boolean recoveryAllowed = DaemonService.allowsAutomaticRecovery(this);
        if (!shouldRunRecovery(foregroundActive, recoveryAllowed)) {
            return runsAsynchronously();
        }
        try {
            PrivilegeAppOptimizer.notifyBootFinish();
            VActivityManager.get().reconcileTrustedGmsCloudMessaging();
        } catch (Throwable ignored) {
            // A later persisted run retries after the engine service is available again.
        }
        // Maintenance above is synchronous. Returning true without calling jobFinished() leaves
        // JobScheduler believing the job is still running and prevents reliable future recovery.
        return runsAsynchronously();
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    public static void scheduleJob(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        try {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);

            if (jobScheduler == null) {
                return;
            }

            JobInfo jobInfo = new JobInfo.Builder(
                    JOB_ID, new ComponentName(context, DaemonJobService.class))
                    .setRequiresCharging(false)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIODIC_INTERVAL_MILLIS)
                    .setPersisted(true)
                    .build();

            jobScheduler.schedule(jobInfo);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void cancelJob(Context context) {
        try {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(
                    JOB_SCHEDULER_SERVICE);
            if (jobScheduler != null) {
                jobScheduler.cancel(JOB_ID);
            }
        } catch (Exception ignored) {
            // Cancellation is best effort; a later inactive run cancels itself before recovery.
        }
    }

    static boolean shouldRunRecovery(boolean foregroundActive, boolean recoveryAllowed) {
        return foregroundActive && recoveryAllowed;
    }

    static boolean runsAsynchronously() {
        return false;
    }
}
