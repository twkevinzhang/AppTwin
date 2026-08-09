package com.lody.virtual.server.job;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.text.TextUtils;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VJobScheduler;
import com.lody.virtual.client.stub.VASettings;
import com.lody.virtual.helper.utils.AtomicFile;
import com.lody.virtual.helper.utils.Singleton;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.server.IJobScheduler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


/**
 * @author Lody
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class VJobSchedulerService extends IJobScheduler.Stub {

    private static final String TAG = VJobScheduler.class.getSimpleName();

    private static final int JOB_FILE_VERSION = 1;
    private final Map<JobId, JobConfig> mJobStore = new HashMap<>();
    private int mGlobalJobId;

    private final JobScheduler mScheduler = (JobScheduler)
            VirtualCore.get().getContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);

    private final ComponentName mJobProxyComponent;

    private VJobSchedulerService() {
        mJobProxyComponent = new ComponentName(VirtualCore.get().getHostPkg(), VASettings.STUB_JOB);
        readJobs();
    }

    private static final Singleton<VJobSchedulerService> gDefault = new Singleton<VJobSchedulerService>() {
        @Override
        protected VJobSchedulerService create() {
            return new VJobSchedulerService();
        }
    };

    public static VJobSchedulerService get() {
        return gDefault.get();
    }


    public static final class JobId implements Parcelable {

        public int vuid;
        public String packageName;
        /**
         * The id given by User.
         */
        public int clientJobId;

        JobId(int vuid, String packageName, int id) {
            this.vuid = vuid;
            this.packageName = packageName;
            this.clientJobId = id;
        }


        JobId(Parcel in) {
            this.vuid = in.readInt();
            this.packageName = in.readString();
            this.clientJobId = in.readInt();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            JobId jobId = (JobId) o;

            return vuid == jobId.vuid
                    && clientJobId == jobId.clientJobId
                    && TextUtils.equals(packageName, jobId.packageName);
        }

        @Override
        public int hashCode() {
            int result = vuid;
            result = 31 * result + (packageName != null ? packageName.hashCode() : 0);
            result = 31 * result + clientJobId;
            return result;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(this.vuid);
            dest.writeString(this.packageName);
            dest.writeInt(this.clientJobId);
        }

        public static final Parcelable.Creator<JobId> CREATOR = new Parcelable.Creator<JobId>() {
            @Override
            public JobId createFromParcel(Parcel source) {
                return new JobId(source);
            }

            @Override
            public JobId[] newArray(int size) {
                return new JobId[size];
            }
        };
    }

    public static final class JobConfig implements Parcelable {

        /**
         * The id given by VA.
         */
        public int virtualJobId;
        public String serviceName;
        public PersistableBundle extras;

        JobConfig(int virtualJobId, String serviceName, PersistableBundle extra) {
            this.virtualJobId = virtualJobId;
            this.serviceName = serviceName;
            this.extras = extra;
        }

        JobConfig(Parcel in) {
            this.virtualJobId = in.readInt();
            this.serviceName = in.readString();
            this.extras = in.readParcelable(PersistableBundle.class.getClassLoader());
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(this.virtualJobId);
            dest.writeString(this.serviceName);
            dest.writeParcelable(this.extras, flags);
        }

        public static final Parcelable.Creator<JobConfig> CREATOR = new Parcelable.Creator<JobConfig>() {
            @Override
            public JobConfig createFromParcel(Parcel source) {
                return new JobConfig(source);
            }

            @Override
            public JobConfig[] newArray(int size) {
                return new JobConfig[size];
            }
        };
    }


    @Override
    public int schedule(JobInfo job) throws RemoteException {
        int vuid = VBinder.getCallingUid();
        int id = job.getId();
        ComponentName service = job.getService();
        if (service == null) throw new SecurityException("Job service is required");
        int userId = com.lody.virtual.os.VUserHandle.getUserId(vuid);
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(service.getPackageName(), userId);
        if (com.lody.virtual.server.pm.VPackageManagerService.get().getServiceInfo(
                service, 0, userId) == null) {
            throw new SecurityException("Job service is not installed for caller");
        }
        JobId jobId = new JobId(vuid, service.getPackageName(), id);
        JobConfig config;
        synchronized (mJobStore) {
            config = mJobStore.get(jobId);
            if (config == null) {
                config = new JobConfig(mGlobalJobId++, service.getClassName(), job.getExtras());
                mJobStore.put(jobId, config);
            } else {
                config.serviceName = service.getClassName();
                config.extras = job.getExtras();
            }
            saveJobs();
        }
        mirror.android.app.job.JobInfo.jobId.set(job, config.virtualJobId);
        mirror.android.app.job.JobInfo.service.set(job, mJobProxyComponent);
        return mScheduler.schedule(job);
    }

    private void saveJobs() {
        File jobFile = VEnvironment.getJobConfigFile();
        AtomicFile atomicFile = new AtomicFile(jobFile);
        Parcel p = Parcel.obtain();
        FileOutputStream fos = null;
        try {
            p.writeInt(JOB_FILE_VERSION);
            p.writeInt(mJobStore.size());
            for (Map.Entry<JobId, JobConfig> entry : mJobStore.entrySet()) {
                entry.getKey().writeToParcel(p, 0);
                entry.getValue().writeToParcel(p, 0);
            }
            fos = atomicFile.startWrite();
            fos.write(p.marshall());
            atomicFile.finishWrite(fos);
            fos = null;
        } catch (Exception e) {
            atomicFile.failWrite(fos);
            throw new IllegalStateException("Unable to persist virtual job state", e);
        } finally {
            p.recycle();
        }
    }

    private void readJobs() {
        File jobFile = VEnvironment.getJobConfigFile();
        if (!jobFile.exists()) {
            return;
        }
        Parcel p = Parcel.obtain();
        try {
            byte[] bytes = new AtomicFile(jobFile).readFully();
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            int version = p.readInt();
            if (version != JOB_FILE_VERSION) {
                throw new IOException("Bad version of job file: " + version);
            }
            Map<JobId, JobConfig> loadedJobs = new HashMap<>();
            int nextGlobalJobId = 0;
            int count = p.readInt();
            if (count < 0 || count > 100_000) {
                throw new IOException("Invalid virtual job count");
            }
            for (int i = 0; i < count; i++) {
                JobId jobId = new JobId(p);
                JobConfig config = new JobConfig(p);
                loadedJobs.put(jobId, config);
                nextGlobalJobId = Math.max(nextGlobalJobId, config.virtualJobId + 1);
            }
            if (p.dataAvail() != 0) {
                throw new IOException("Trailing virtual job state data");
            }
            synchronized (mJobStore) {
                mJobStore.clear();
                mJobStore.putAll(loadedJobs);
                mGlobalJobId = nextGlobalJobId;
            }
        } catch (Exception e) {
            synchronized (mJobStore) {
                mJobStore.clear();
                mGlobalJobId = 0;
            }
            new AtomicFile(jobFile).delete();
        } finally {
            p.recycle();
        }

    }

    @Override
    public void cancel(int jobId) throws RemoteException {
        int vuid = VBinder.getCallingUid();
        synchronized (mJobStore) {
            boolean changed = false;
            Iterator<Map.Entry<JobId, JobConfig>> iterator = mJobStore.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<JobId, JobConfig> entry = iterator.next();
                JobId job = entry.getKey();
                JobConfig config = entry.getValue();
                if (job.vuid == vuid && job.clientJobId == jobId) {
                    changed = true;
                    mScheduler.cancel(config.virtualJobId);
                    iterator.remove();
                    break;
                }
            }
            if (changed) {
                saveJobs();
            }
        }
    }

    @Override
    public void cancelAll() throws RemoteException {
        int vuid = VBinder.getCallingUid();
        synchronized (mJobStore) {
            boolean changed = removeMatchingJobs(
                    mJobStore, job -> job.vuid == vuid, mScheduler::cancel);
            if (changed) {
                saveJobs();
            }
        }
    }

    /** Removes every persisted and platform-scheduled job owned by one virtual user. */
    public void clearUserState(int userId) {
        synchronized (mJobStore) {
            removeMatchingJobs(
                    mJobStore,
                    job -> VUserHandle.getUserId(job.vuid) == userId,
                    mScheduler::cancel);
            // Always rewrite. A previous atomic write may have failed after the in-memory entries
            // were removed; an idempotent retry must still erase the durable records.
            saveJobs();
        }
    }

    /** Cancels durable jobs for one package binding without affecting the user's other apps. */
    public void clearPackageState(String packageName, int userId) {
        synchronized (mJobStore) {
            removeMatchingJobs(
                    mJobStore,
                    job -> VUserHandle.getUserId(job.vuid) == userId
                            && TextUtils.equals(job.packageName, packageName),
                    mScheduler::cancel);
            // Rewrite even when already empty so retry after a failed atomic write converges.
            saveJobs();
        }
    }

    public boolean hasPackageState(String packageName, int userId) {
        synchronized (mJobStore) {
            for (JobId job : mJobStore.keySet()) {
                if (VUserHandle.getUserId(job.vuid) == userId
                        && TextUtils.equals(job.packageName, packageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    interface JobMatcher {
        boolean matches(JobId jobId);
    }

    interface JobCanceller {
        void cancel(int virtualJobId);
    }

    static boolean removeMatchingJobs(Map<JobId, JobConfig> jobs, JobMatcher matcher,
                                      JobCanceller canceller) {
        boolean changed = false;
        Iterator<Map.Entry<JobId, JobConfig>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<JobId, JobConfig> entry = iterator.next();
            if (matcher.matches(entry.getKey())) {
                canceller.cancel(entry.getValue().virtualJobId);
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public List<JobInfo> getAllPendingJobs() throws RemoteException {
        int vuid = VBinder.getCallingUid();
        List<JobInfo> jobs = mScheduler.getAllPendingJobs();
        synchronized (mJobStore) {
            Iterator<JobInfo> iterator = jobs.listIterator();
            while (iterator.hasNext()) {
                JobInfo job = iterator.next();
                if (!VASettings.STUB_JOB.equals(job.getService().getClassName())) {
                    // Schedule by Host, invisible in VA.
                    iterator.remove();
                    continue;
                }
                Map.Entry<JobId, JobConfig> jobEntry = findJobByVirtualJobId(job.getId());
                if (jobEntry == null) {
                    iterator.remove();
                    continue;
                }
                JobId jobId = jobEntry.getKey();
                JobConfig config = jobEntry.getValue();
                if (jobId.vuid != vuid) {
                    iterator.remove();
                    continue;
                }
                mirror.android.app.job.JobInfo.jobId.set(job, jobId.clientJobId);
                mirror.android.app.job.JobInfo.service.set(job, new ComponentName(jobId.packageName, config.serviceName));
            }
        }
        return jobs;
    }

    @Override
    public int enqueue(JobInfo job, JobWorkItem work) throws RemoteException {
        return schedule(job);
    }

    @Override
    public JobInfo getPendingJob(int i) throws RemoteException {
        return null;
    }


    public Map.Entry<JobId, JobConfig> findJobByVirtualJobId(int virtualJobId) {
        synchronized (mJobStore) {
            for (Map.Entry<JobId, JobConfig> entry : mJobStore.entrySet()) {
                if (entry.getValue().virtualJobId == virtualJobId) {
                    return entry;
                }
            }
            return null;
        }
    }

}
