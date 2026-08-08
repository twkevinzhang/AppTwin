package com.lody.virtual.client.stub;

import android.annotation.TargetApi;
import android.app.Service;
import android.app.job.IJobCallback;
import android.app.job.IJobService;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.hook.proxies.am.ActivityManagerStub;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.helper.collection.SparseArray;
import com.lody.virtual.os.VUserHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.lody.virtual.server.job.VJobSchedulerService.JobConfig;
import static com.lody.virtual.server.job.VJobSchedulerService.JobId;
import static com.lody.virtual.server.job.VJobSchedulerService.get;

/**
 * @author Lody
 *         <p>
 *         This service running on the Server process.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class StubJob extends Service {

    private static final String TAG = StubJob.class.getSimpleName();
    private final SparseArray<JobSession> mJobSessions = new SparseArray<>();
    private JobScheduler mScheduler;
    private boolean mDestroyed;
    private final IJobService mService = new IJobService.Stub() {

        @Override
        public void startJob(JobParameters jobParams) throws RemoteException {
            int jobId = jobParams.getJobId();
            IBinder binder = mirror.android.app.job.JobParameters.callback.get(jobParams);
            IJobCallback callback = IJobCallback.Stub.asInterface(binder);
            Map.Entry<JobId, JobConfig> entry = get().findJobByVirtualJobId(jobId);
            if (entry == null) {
                emptyCallback(callback, jobId);
                mScheduler.cancel(jobId);
            } else {
                JobId key = entry.getKey();
                JobConfig config = entry.getValue();
                JobSession session;
                synchronized (mJobSessions) {
                    session = mJobSessions.get(jobId);
                    if (session != null) {
                        // A session for this virtual job is already active.
                        session = null;
                    } else if (mDestroyed) {
                        session = null;
                    } else {
                        session = new JobSession(jobId, callback, jobParams);
                        mirror.android.app.job.JobParameters.callback.set(jobParams, session.asBinder());
                        mirror.android.app.job.JobParameters.jobId.set(jobParams, key.clientJobId);
                        // Register before binding so stop/destroy can see an in-flight bind.
                        mJobSessions.put(jobId, session);
                    }
                }
                if (session == null) {
                    emptyCallback(callback, jobId);
                    return;
                }

                Intent service = new Intent();
                service.setComponent(new ComponentName(key.packageName, config.serviceName));
                service.putExtra("_VA_|_user_id_", VUserHandle.getUserId(key.vuid));
                boolean bound = session.bindIfActive(service);
                if (!bound) {
                    session.finishBeforeStart();
                    mScheduler.cancel(jobId);
                    get().cancel(jobId);
                }
            }
        }

        @Override
        public void stopJob(JobParameters jobParams) throws RemoteException {
            int jobId = jobParams.getJobId();
            JobSession session;
            synchronized (mJobSessions) {
                session = mJobSessions.get(jobId);
            }
            if (session != null) {
                session.stopSession(true);
            }
        }
    };

    /**
     * Make JobScheduler happy.
     */
    private void emptyCallback(IJobCallback callback, int jobId) {
        try {
            callback.acknowledgeStartMessage(jobId, false);
            callback.jobFinished(jobId, false);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        InvocationStubManager.getInstance().checkEnv(ActivityManagerStub.class);
        mScheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mService.asBinder();
    }

    @Override
    public void onDestroy() {
        List<JobSession> sessions = new ArrayList<>();
        synchronized (mJobSessions) {
            mDestroyed = true;
            for (int i = 0; i < mJobSessions.size(); i++) {
                sessions.add(mJobSessions.valueAt(i));
            }
            mJobSessions.clear();
        }
        for (JobSession session : sessions) {
            session.stopSession(true);
        }
        super.onDestroy();
    }

    private void removeSession(JobSession session) {
        synchronized (mJobSessions) {
            if (mJobSessions.get(session.jobId) == session) {
                mJobSessions.remove(session.jobId);
            }
        }
    }

    /** Pure state seam that makes cleanup idempotent across Binder/lifecycle races. */
    static final class SessionState {
        enum CleanupAction {
            ALREADY_CLEANED(false, false),
            CLEANED(false, true),
            CLEANED_AND_UNBIND(true, true);

            private final boolean shouldUnbind;
            private final boolean firstCleanup;

            CleanupAction(boolean shouldUnbind, boolean firstCleanup) {
                this.shouldUnbind = shouldUnbind;
                this.firstCleanup = firstCleanup;
            }

            boolean shouldUnbind() {
                return shouldUnbind;
            }

            boolean isFirstCleanup() {
                return firstCleanup;
            }
        }

        private boolean dispatcherRegistered;
        private boolean cleanupRequested;
        private boolean unbindClaimed;
        private boolean finishCallbackClaimed;

        synchronized boolean onBindingStarted() {
            if (cleanupRequested || dispatcherRegistered) {
                return false;
            }
            // ContextImpl obtains and stores its ServiceDispatcher before the remote
            // bind call returns. A false return value or exception therefore does not
            // prove that there is no dispatcher to release.
            dispatcherRegistered = true;
            return true;
        }

        synchronized CleanupAction requestCleanup() {
            if (cleanupRequested) {
                return CleanupAction.ALREADY_CLEANED;
            }
            cleanupRequested = true;
            return claimUnbindIfNeeded()
                    ? CleanupAction.CLEANED_AND_UNBIND
                    : CleanupAction.CLEANED;
        }

        synchronized boolean isCleanupRequested() {
            return cleanupRequested;
        }

        synchronized boolean claimFinishCallback() {
            if (finishCallbackClaimed) {
                return false;
            }
            finishCallbackClaimed = true;
            return true;
        }

        private boolean claimUnbindIfNeeded() {
            if (!dispatcherRegistered || !cleanupRequested || unbindClaimed) {
                return false;
            }
            unbindClaimed = true;
            return true;
        }
    }

    private final class JobSession extends IJobCallback.Stub implements ServiceConnection {

        private final int jobId;
        private final IJobCallback clientCallback;
        private final JobParameters jobParams;
        private final SessionState state = new SessionState();
        private final Object bindingLock = new Object();
        private volatile IJobService clientJobService;

        JobSession(int jobId, IJobCallback clientCallback, JobParameters jobParams) {
            this.jobId = jobId;
            this.clientCallback = clientCallback;
            this.jobParams = jobParams;
        }

        @Override
        public void acknowledgeStartMessage(int jobId, boolean ongoing) throws RemoteException {
            try {
                clientCallback.acknowledgeStartMessage(jobId, ongoing);
            } catch (RemoteException e) {
                stopSession(true);
                throw e;
            }
            if (!ongoing) {
                // acknowledgeStart(false) is terminal; suppress any late disconnect finish.
                state.claimFinishCallback();
                stopSession(false);
            }
        }

        @Override
        public void acknowledgeStopMessage(int jobId, boolean reschedule) throws RemoteException {
            try {
                clientCallback.acknowledgeStopMessage(jobId, reschedule);
            } finally {
                stopSession(false);
            }
        }

        @Override
        public void jobFinished(int jobId, boolean reschedule) throws RemoteException {
            try {
                if (state.claimFinishCallback()) {
                    clientCallback.jobFinished(jobId, reschedule);
                }
            } finally {
                stopSession(false);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (state.isCleanupRequested()) {
                return;
            }
            clientJobService = IJobService.Stub.asInterface(service);
            if (clientJobService == null) {
                finishBeforeStart();
                return;
            }
            if (state.isCleanupRequested()) {
                return;
            }
            try {
                clientJobService.startJob(jobParams);
            } catch (RemoteException e) {
                forceFinishJob();
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            forceFinishJob();
        }

        void forceFinishJob() {
            try {
                if (state.claimFinishCallback()) {
                    clientCallback.jobFinished(jobId, false);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            } finally {
                stopSession(false);
            }
        }

        void finishBeforeStart() {
            try {
                if (state.claimFinishCallback()) {
                    try {
                        clientCallback.acknowledgeStartMessage(jobId, false);
                    } catch (RemoteException ignored) {
                        // The scheduler callback is already gone; cleanup must still run.
                    }
                    try {
                        clientCallback.jobFinished(jobId, false);
                    } catch (RemoteException ignored) {
                        // The scheduler callback is already gone; cleanup must still run.
                    }
                }
            } finally {
                stopSession(false);
            }
        }

        boolean bindIfActive(Intent service) {
            synchronized (bindingLock) {
                if (!state.onBindingStarted()) {
                    return false;
                }
                try {
                    // ContextImpl registers the ServiceConnection before the Binder call returns.
                    // Record that dispatcher before invoking bindService(), even when the remote
                    // bind later returns false or throws. Keep lifecycle cleanup outside this
                    // window so onDestroy cannot return with an in-flight dispatcher.
                    return StubJob.this.bindService(service, this, 0);
                } catch (Throwable e) {
                    VLog.e(TAG, e);
                    return false;
                }
            }
        }

        void stopSession(boolean stopClient) {
            removeSession(this);
            SessionState.CleanupAction action;
            synchronized (bindingLock) {
                // Wait for an in-flight Context.bindService() to return before claiming cleanup.
                // ActivityThread does not check for leaked dispatchers until onDestroy returns.
                action = state.requestCleanup();
            }
            if (!action.isFirstCleanup()) {
                return;
            }

            IJobService service = clientJobService;
            if (stopClient && service != null) {
                try {
                    service.stopJob(jobParams);
                } catch (Throwable e) {
                    VLog.e(TAG, e);
                }
            }
            if (action.shouldUnbind()) {
                unbindSafely();
            }
        }

        private void unbindSafely() {
            try {
                StubJob.this.unbindService(this);
            } catch (Throwable e) {
                // The framework may have already dropped a dead connection.
                VLog.e(TAG, e);
            }
        }
    }

}
