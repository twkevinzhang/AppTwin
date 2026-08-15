package com.lody.virtual.server.am;

import android.app.ActivityManager;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import com.lody.virtual.client.IVClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;
import com.lody.virtual.client.env.SpecialComponentList;
import com.lody.virtual.client.ipc.ProviderCall;
import com.lody.virtual.client.ipc.VNotificationManager;
import com.lody.virtual.client.stub.StubProcessContract;
import com.lody.virtual.client.stub.VASettings;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.collection.SparseArray;
import com.lody.virtual.helper.compat.ActivityManagerCompat;
import com.lody.virtual.helper.compat.ApplicationThreadCompat;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.helper.compat.ServiceConnectionCompat;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.AppTaskInfo;
import com.lody.virtual.remote.BadgerInfo;
import com.lody.virtual.remote.PendingIntentData;
import com.lody.virtual.remote.PendingResultData;
import com.lody.virtual.remote.PreparedActivityLaunch;
import com.lody.virtual.remote.VParceledListSlice;
import com.lody.virtual.server.IActivityManager;
import com.lody.virtual.server.interfaces.IProcessObserver;
import com.lody.virtual.server.pm.PackageCacheManager;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.VAppManagerService;
import com.lody.virtual.server.pm.VPackageManagerService;
import com.lody.virtual.server.pm.VUserManagerService;
import com.lody.virtual.server.secondary.BinderDelegateService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static android.os.Process.killProcess;
import static com.lody.virtual.os.VUserHandle.getUserId;

/**
 * @author Lody
 */
public class VActivityManagerService extends IActivityManager.Stub
        implements IsolatedGuestClient.Listener {

    private static final boolean BROADCAST_NOT_STARTED_PKG = false;
    private static final long SERVICE_STARTUP_TIMEOUT_MS = 15_000L;
    private static final long PREPARED_LAUNCH_ACK_TIMEOUT_MS = 5_000L;
    private static final int STUB_INIT_MAX_ATTEMPTS = 4;
    private static final long STUB_INIT_RETRY_DELAY_MS = 100L;

    private static final AtomicReference<VActivityManagerService> sService = new AtomicReference<>();
    private static final String TAG = VActivityManagerService.class.getSimpleName();
    private final SparseArray<ProcessRecord> mPidsSelfLocked = new SparseArray<ProcessRecord>();
    private final ProcessStartGate mProcessStartGate = new ProcessStartGate();
    private final ActivityStack mMainStack = new ActivityStack(this);
    private final Set<ServiceRecord> mHistory = new HashSet<ServiceRecord>();
    private final ProcessMap<ProcessRecord> mProcessNames = new ProcessMap<ProcessRecord>();
    private final LogicalProcessOwnerRegistry<ProcessRecord> mLogicalProcessOwners =
            new LogicalProcessOwnerRegistry<>(VActivityManagerService::isLogicalOwnerAlive);
    private final LogicalProcessOwnerRegistry<ProcessRecord> mIsolatedServiceOwners =
            new LogicalProcessOwnerRegistry<>(VActivityManagerService::isIsolatedOwnerAlive);
    private final Map<IsolatedGuestClient, ProcessRecord> mIsolatedClients =
            new IdentityHashMap<>();
    private final PendingIntents mPendingIntents = new PendingIntents();
    private final PreparedActivityLaunchRegistry mPreparedActivityLaunches =
            new PreparedActivityLaunchRegistry();
    private GmsBackgroundKeepAlive mGmsBackgroundKeepAlive;
    private LinePushProcessGuard mLinePushProcessGuard;
    private Handler mServiceHandler;
    private ActivityManager am = (ActivityManager) VirtualCore.get().getContext()
            .getSystemService(Context.ACTIVITY_SERVICE);
    private NotificationManager nm = (NotificationManager) VirtualCore.get().getContext()
            .getSystemService(Context.NOTIFICATION_SERVICE);

    public static VActivityManagerService get() {
        return sService.get();
    }

    public static void systemReady(Context context) {
        new VActivityManagerService().onCreate(context);
    }

    private static ServiceInfo resolveServiceInfo(Intent service, int userId) {
        if (service != null) {
            ServiceInfo serviceInfo = VirtualCore.get().resolveServiceInfo(service, userId);
            if (serviceInfo != null) {
                return serviceInfo;
            }
        }
        return null;
    }

    public void onCreate(Context context) {
        AttributeCache.init(context);
        mServiceHandler = new Handler(Looper.getMainLooper());
        mGmsBackgroundKeepAlive = new GmsBackgroundKeepAlive(context);
        mLinePushProcessGuard = new LinePushProcessGuard(context, mServiceHandler);
        PackageManager pm = context.getPackageManager();
        PackageInfo packageInfo = null;
        try {
            packageInfo = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        if (packageInfo == null) {
            throw new RuntimeException("Unable to found PackageInfo : " + context.getPackageName());
        }
        sService.set(this);

    }


    @Override
    public int startActivity(Intent intent, ActivityInfo info, IBinder resultTo, Bundle options, String resultWho, int requestCode, int userId) {
        enforceCallerUserOrHost(userId);
        synchronized (this) {
            return mMainStack.startActivityLocked(userId, intent, info, resultTo, options, resultWho, requestCode);
        }
    }

    @Override
    public int startActivities(Intent[] intents, String[] resolvedTypes, IBinder token, Bundle options, int userId) {
        enforceCallerUserOrHost(userId);
        synchronized (this) {
            ActivityInfo[] infos = new ActivityInfo[intents.length];
            for (int i = 0; i < intents.length; i++) {
                ActivityInfo ai = VirtualCore.get().resolveActivityInfo(intents[i], userId);
                if (ai == null) {
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                infos[i] = ai;

            }
            return mMainStack.startActivitiesLocked(userId, intents, infos, resolvedTypes, token, options);
        }
    }

    @Override
    public PreparedActivityLaunch prepareActivityLaunch(
            Intent intent, String expectedPackage, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        if (!VUserManagerService.get().exists(userId)) {
            return PreparedActivityLaunch.failure("Invalid virtual user");
        }
        if (!isPreparedLaunchIntentScopedToPackage(
                expectedPackage,
                intent == null ? null : intent.getPackage(),
                intent == null || intent.getComponent() == null
                        ? null : intent.getComponent().getPackageName())) {
            return PreparedActivityLaunch.failure("Intent is not scoped to the expected package");
        }
        if (!VAppManagerService.get().isAppInstalledAsUser(userId, expectedPackage)) {
            return PreparedActivityLaunch.failure("Expected package is not installed for user");
        }

        Intent request = new Intent(intent);
        ActivityInfo resolved = VirtualCore.get().resolveActivityInfo(request, userId);
        ComponentName resolvedComponent = request.getComponent();
        if (!isPreparedLaunchResolutionValid(
                expectedPackage,
                resolved == null ? null : resolved.packageName,
                resolved == null ? null : resolved.name,
                resolvedComponent == null ? null : resolvedComponent.getPackageName(),
                resolvedComponent == null ? null : resolvedComponent.getClassName())) {
            return PreparedActivityLaunch.failure(
                    "Resolved activity does not match the expected package");
        }

        synchronized (this) {
            PreparedActivityLaunch prepared = mMainStack.prepareActivityLaunchLocked(
                    userId, request, resolved);
            if (prepared.isHostStartRequired()
                    && !registerPreparedActivityLaunch(prepared.getLaunchId(), userId, null)) {
                return PreparedActivityLaunch.failure("Unable to register prepared launch");
            }
            return prepared;
        }
    }

    boolean registerPreparedActivityLaunch(String launchId, int userId, IBinder expectedToken) {
        try {
            mPreparedActivityLaunches.register(launchId, userId, expectedToken);
        } catch (IllegalArgumentException invalidLaunch) {
            return false;
        }
        mServiceHandler.postDelayed(
                () -> mPreparedActivityLaunches.cancel(launchId),
                PREPARED_LAUNCH_ACK_TIMEOUT_MS);
        return true;
    }

    @Override
    public boolean awaitPreparedActivityLaunch(String launchId, long timeoutMs) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        long boundedTimeout = Math.max(0L,
                Math.min(timeoutMs, PREPARED_LAUNCH_ACK_TIMEOUT_MS));
        return mPreparedActivityLaunches.await(launchId, boundedTimeout);
    }

    @Override
    public void cancelPreparedActivityLaunch(String launchId) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        mPreparedActivityLaunches.cancel(launchId);
    }

    static boolean isPreparedLaunchIntentScopedToPackage(
            String expectedPackage, String intentPackage, String componentPackage) {
        if (expectedPackage == null || expectedPackage.isEmpty()
                || (intentPackage == null && componentPackage == null)) {
            return false;
        }
        return (intentPackage == null || expectedPackage.equals(intentPackage))
                && (componentPackage == null || expectedPackage.equals(componentPackage));
    }

    static boolean isPreparedLaunchResolutionValid(
            String expectedPackage, String activityPackage, String activityName,
            String componentPackage, String componentName) {
        return expectedPackage != null
                && expectedPackage.equals(activityPackage)
                && activityName != null
                && ((componentPackage == null && componentName == null)
                || (expectedPackage.equals(componentPackage)
                && activityName.equals(componentName)));
    }

    @Override
    public String getPackageForIntentSender(IBinder binder) {
        PendingIntentData data = mPendingIntents.getPendingIntent(binder);
        if (data != null) {
            return data.creator;
        }
        return null;
    }


    @Override
    public PendingIntentData getPendingIntent(IBinder binder) {
        return mPendingIntents.getPendingIntent(binder);
    }

    @Override
    public void addPendingIntent(IBinder binder, String creator) {
        mPendingIntents.addPendingIntent(
                binder, creator, VUserHandle.getUserId(VBinder.getCallingUid()));
    }

    @Override
    public void removePendingIntent(IBinder binder) {
        mPendingIntents.removePendingIntent(binder);
    }

    public void clearPendingIntentState(String packageName, int userId) {
        mPendingIntents.clearPackageUser(packageName, userId);
    }

    public void clearPendingIntentState(int userId) {
        mPendingIntents.clearUser(userId);
    }

    /** Synchronously retires all runtime ownership before a virtual user id can be reused. */
    public boolean clearUserRuntimeState(int userId) {
        retireUserProcesses(userId, "user-cleanup");
        mPendingIntents.clearUser(userId);
        mPreparedActivityLaunches.cancelUser(userId);
        return !hasUserRuntimeState(userId);
    }

    private void retireUserProcesses(int userId, String reason) {
        List<ProcessRecord> processes = new ArrayList<>();
        synchronized (mPidsSelfLocked) {
            for (int i = mPidsSelfLocked.size() - 1; i >= 0; i--) {
                ProcessRecord process = mPidsSelfLocked.valueAt(i);
                if (process.userId == userId) processes.add(process);
            }
        }
        for (ProcessRecord process : processes) {
            cleanupProcessGeneration(process, reason, true);
        }
    }

    private boolean hasUserRuntimeState(int userId) {
        synchronized (mPidsSelfLocked) {
            for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                if (mPidsSelfLocked.valueAt(i).userId == userId) return true;
            }
        }
        synchronized (mHistory) {
            for (ServiceRecord service : mHistory) {
                if (service.process != null && service.process.userId == userId) return true;
            }
        }
        return mPendingIntents.hasUser(userId);
    }

    private void enforceCallerUserOrHost(int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceCallerUserOrHost(userId);
    }

    public boolean hasPendingIntentState(String packageName, int userId) {
        return mPendingIntents.hasPackageUser(packageName, userId);
    }

    @Override
    public int getSystemPid() {
        return VirtualCore.get().myUid();
    }

    @Override
    public boolean onActivityCreated(ComponentName component, ComponentName caller, IBinder token,
            Intent intent, String affinity, int taskId, int launchMode, int flags,
            String preparedLaunchId) {
        int pid = Binder.getCallingPid();
        ProcessRecord targetApp = findProcessLocked(pid);
        if (targetApp == null) {
            return false;
        }
        boolean preparedLaunchPending = preparedLaunchId != null
                && mPreparedActivityLaunches.isPending(preparedLaunchId, targetApp.userId);
        boolean accepted = mMainStack.onActivityCreated(targetApp, component, caller, token,
                intent, affinity, taskId, launchMode, flags, preparedLaunchId,
                preparedLaunchId != null && !preparedLaunchPending);
        if (!accepted) {
            mPreparedActivityLaunches.cancelForUser(preparedLaunchId, targetApp.userId);
            return false;
        }
        if (preparedLaunchPending
                && !mPreparedActivityLaunches.attachActivity(
                preparedLaunchId, targetApp.userId, token)) {
            mMainStack.onActivityDestroyed(targetApp.userId, token);
            mPreparedActivityLaunches.cancelForUser(preparedLaunchId, targetApp.userId);
            return false;
        }
        return true;
    }

    @Override
    public void onActivityResumed(int userId, IBinder token) {
        enforceCallerUserOrHost(userId);
        if (mMainStack.onActivityResumed(userId, token)) {
            mPreparedActivityLaunches.acknowledge(userId, token);
        }
    }

    @Override
    public boolean onActivityDestroyed(int userId, IBinder token) {
        enforceCallerUserOrHost(userId);
        ActivityRecord r = mMainStack.onActivityDestroyed(userId, token);
        mPreparedActivityLaunches.cancelActivity(userId, token);
        return r != null;
    }

    @Override
    public AppTaskInfo getTaskInfo(int taskId) {
        int taskUserId = mMainStack.getTaskUserId(taskId);
        if (!canCallerObserveUser(taskUserId)) return null;
        return mMainStack.getTaskInfo(taskId);
    }

    @Override
    public String getPackageForToken(int userId, IBinder token) {
        enforceCallerUserOrHost(userId);
        return mMainStack.getPackageForToken(userId, token);
    }

    @Override
    public ComponentName getActivityClassForToken(int userId, IBinder token) {
        enforceCallerUserOrHost(userId);
        return mMainStack.getActivityClassForToken(userId, token);
    }


    @Override
    public IBinder acquireProviderClient(int userId, ProviderInfo info) {
        enforceCallerUserOrHost(userId);
        ProcessRecord callerApp;
        synchronized (mPidsSelfLocked) {
            callerApp = findProcessLocked(VBinder.getCallingPid());
        }
        if (callerApp == null) {
            throw new SecurityException("Who are you?");
        }
        if (info == null || info.packageName == null || info.name == null) {
            throw new SecurityException("Invalid provider identity");
        }
        ProviderInfo resolved = com.lody.virtual.server.pm.VPackageManagerService.get()
                .getProviderInfo(new ComponentName(info.packageName, info.name),
                        PackageManager.GET_META_DATA, userId);
        if (resolved == null) throw new SecurityException("Provider is not installed for user");
        String processName = resolved.processName;
        ProcessRecord r = startProcessIfNeedLocked(processName, userId, resolved.packageName);
        if (r != null && r.client.asBinder().pingBinder()) {
            try {
                return r.client.acquireProviderClient(resolved);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public ComponentName getCallingActivity(int userId, IBinder token) {
        enforceCallerUserOrHost(userId);
        return mMainStack.getCallingActivity(userId, token);
    }

    @Override
    public String getCallingPackage(int userId, IBinder token) {
        enforceCallerUserOrHost(userId);
        return mMainStack.getCallingPackage(userId, token);
    }


    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (Throwable e) {
            e.printStackTrace();
            throw e;
        }
    }

    private void addRecord(ServiceRecord r) {
        synchronized (mHistory) {
            mHistory.add(r);
        }
    }

    private void removeRecord(ServiceRecord r) {
        synchronized (mHistory) {
            mHistory.remove(r);
        }
    }

    private boolean containsServiceRecordLocked(ServiceRecord r) {
        synchronized (mHistory) {
            return mHistory.contains(r);
        }
    }

    private ServiceRecord findRecordLocked(int userId, ServiceInfo serviceInfo) {
        synchronized (mHistory) {
            for (ServiceRecord r : mHistory) {
                // If service is not created, and bindService with the flag that is
                // not BIND_AUTO_CREATE, r.process is null
                if ((r.process == null || r.process.userId == userId)
                        && ComponentUtils.isSameComponent(serviceInfo, r.serviceInfo)) {
                    return r;
                }
            }
            return null;
        }
    }

    private ServiceRecord findRecordLocked(IServiceConnection connection) {
        synchronized (mHistory) {
            for (ServiceRecord r : mHistory) {
                if (r.containConnection(connection)) {
                    return r;
                }
            }
            return null;
        }
    }


    @Override
    public ComponentName startService(IBinder caller, Intent service, String resolvedType, int userId) {
        enforceCallerUserOrHost(userId);
        return startServiceCommon(service, true, userId);
    }

    private ComponentName startServiceCommon(Intent service,
                                             boolean scheduleServiceArgs, int userId) {
        ServiceInfo serviceInfo = resolveServiceInfo(service, userId);
        if (serviceInfo == null) {
            VLog.w(TAG, "startService unresolved: " + service + " user=" + userId);
            return null;
        }
        VLog.i(TAG, "startService " + service + " resolved="
                + ComponentUtils.toComponentName(serviceInfo) + " user=" + userId);
        final boolean isolatedProcess = isIsolatedProcess(serviceInfo);
        // Isolated services commonly provide an app's anti-abuse identity and are often bound
        // only once. Dropping that first BIND_AUTO_CREATE request leaves the app permanently
        // without the service, so serialize this rare path instead of failing fast on contention.
        final ProcessRecord targetApp = isolatedProcess
                ? startIsolatedServiceProcess(serviceInfo, userId)
                : scheduleServiceArgs
                ? startProcessIfNeedLocked(ComponentUtils.getProcessName(serviceInfo), userId,
                serviceInfo.packageName, false)
                : tryStartProcessForBinding(ComponentUtils.getProcessName(serviceInfo), userId,
                serviceInfo.packageName, false);
        if (targetApp == null) {
            VLog.e(TAG, "Unable to start new Process for : "
                    + ComponentUtils.toComponentName(serviceInfo));
            return null;
        }
        final ServiceRecord r;
        PendingServiceOperation startArgsOperation = null;
        boolean scheduleCreate = false;
        synchronized (this) {
            ServiceRecord record = findRecordLocked(userId, serviceInfo);
            if (record != null && (record.process != targetApp
                    || !isProcessEndpointActive(record.process))) {
                VLog.w(TAG, "Discarding stale service record "
                        + ComponentUtils.toComponentName(serviceInfo)
                        + " oldPid=" + (record.process == null ? -1 : record.process.pid)
                        + " targetPid=" + targetApp.pid);
                record.retire();
                removeRecord(record);
                record = null;
            }
            if (record == null) {
                record = new ServiceRecord();
                record.startId = 0;
                record.activeSince = SystemClock.elapsedRealtime();
                record.process = targetApp;
                record.serviceInfo = serviceInfo;
                final ServiceRecord ownedRecord = record;
                record.setConnectionDeathCallback((binding, connection, lastConnection) ->
                        onConnectionDied(ownedRecord, binding, connection, lastConnection));
                addRecord(record);
                scheduleCreate = record.markCreateScheduled();
                VLog.i(TAG, "service-created " + ComponentUtils.toComponentName(serviceInfo)
                        + " pid=" + targetApp.pid + " token=" + record);
            } else {
                VLog.i(TAG, "service-reused " + ComponentUtils.toComponentName(serviceInfo)
                        + " pid=" + targetApp.pid + " ownerPid=" + record.process.pid
                        + " token=" + record);
            }
            record.lastActivityTime = SystemClock.uptimeMillis();
            if (scheduleServiceArgs) {
                final ServiceRecord dispatchRecord = record;
                final Intent dispatchIntent = new Intent(service);
                final int startId = ++record.startId;
                final boolean taskRemoved = serviceInfo.applicationInfo != null
                        && serviceInfo.applicationInfo.targetSdkVersion
                        < Build.VERSION_CODES.ECLAIR;
                startArgsOperation = new PendingServiceOperation(
                        targetApp.generation,
                        PendingServiceOperation.Type.START_ARGS,
                        "start-args " + ComponentUtils.toComponentName(serviceInfo)
                                + "#" + startId,
                        () -> {
                            if (!isServiceDispatchValid(targetApp, dispatchRecord)) {
                                return;
                            }
                            VLog.i(TAG, "service-args "
                                    + ComponentUtils.toComponentName(serviceInfo)
                                    + " pid=" + targetApp.pid + " startId=" + startId
                                    + " token=" + dispatchRecord);
                            targetApp.client.scheduleServiceArgs(
                                    dispatchRecord, taskRemoved,
                                    startId, 0, dispatchIntent);
                        },
                        null);
            }
            r = record;
        }

        boolean queuedWork = false;
        if (scheduleCreate) {
            if (!dispatchCreateService(targetApp, r)) {
                failProcessGeneration(targetApp,
                        ProcessLifecycle.TerminalReason.DISPATCH_FAILED,
                        "create-service-failed");
                return null;
            }
            scheduleStartupWatchdog(targetApp);
        }
        if (startArgsOperation != null) {
            queuedWork |= targetApp.lifecycle.enqueue(startArgsOperation);
        }
        if (queuedWork) {
            drainProcessLifecycle(targetApp);
        }
        return ComponentUtils.toComponentName(serviceInfo);
    }

    @Override
    public int stopService(IBinder caller, Intent service, String resolvedType, int userId) {
        enforceCallerUserOrHost(userId);
        ServiceInfo serviceInfo = resolveServiceInfo(service, userId);
        if (serviceInfo == null) {
            return 0;
        }
        final ServiceRecord r;
        synchronized (this) {
            r = findRecordLocked(userId, serviceInfo);
            if (r == null) {
                return 0;
            }
            r.clearStartedState(-1);
        }
        stopServiceCommon(r, ComponentUtils.toComponentName(serviceInfo));
        return 1;
    }

    @Override
    public boolean stopServiceToken(ComponentName className, IBinder token, int startId, int userId) {
        enforceCallerUserOrHost(userId);
        final ServiceRecord r;
        synchronized (this) {
            r = token instanceof ServiceRecord ? (ServiceRecord) token : null;
            if (r == null || !containsServiceRecordLocked(r)
                    || !r.clearStartedState(startId)) {
                return false;
            }
        }
        stopServiceCommon(r, className);
        return true;
    }

    private void stopServiceCommon(ServiceRecord r, ComponentName className) {
        final List<ServiceRecord.IntentBindRecord> bindings;
        final List<ServiceRecord.IntentBindRecord> bindingsToUnbind = new ArrayList<>();
        final List<IServiceConnection> connections = new ArrayList<>();
        synchronized (this) {
            if (!containsServiceRecordLocked(r)) {
                return;
            }
            // stopSelf() only clears the started state. Android must keep a bound service alive
            // until its final client disconnects. Gecko child services rely on this by calling
            // stopSelf() from onBind(); retiring them here makes the content/GPU process exit
            // before it can render its first frame.
            if (r.hasActiveConnections()) {
                VLog.i(TAG, "service-stop-deferred-bound " + className
                        + " connections=" + r.getConnectionCount() + " token=" + r);
                return;
            }
            bindings = new ArrayList<>(r.bindings);
            for (ServiceRecord.IntentBindRecord binding : bindings) {
                connections.addAll(binding.snapshotConnections());
                if (binding.beginUnbindIfNeeded()) {
                    bindingsToUnbind.add(binding);
                }
            }
            r.retire();
            removeRecord(r);
        }
        for (IServiceConnection connection : connections) {
            notifyServiceDisconnected(connection, className);
        }
        for (ServiceRecord.IntentBindRecord binding : bindingsToUnbind) {
            enqueueServiceOperation(r.process, PendingServiceOperation.Type.UNBIND,
                    "unbind-stopped " + className,
                    () -> r.process.client.scheduleUnbindService(
                            r, binding.getBindToken(), binding.intent), null);
        }
        enqueueStopOperation(r, "stop-service");
        drainProcessLifecycle(r.process);
    }

    @Override
    public int bindService(IBinder caller, IBinder token, Intent service, String resolvedType,
                           IServiceConnection connection, int flags, int userId) {
        enforceCallerUserOrHost(userId);
        ServiceInfo serviceInfo = resolveServiceInfo(service, userId);
        if (serviceInfo == null) {
            return 0;
        }
        ServiceRecord r;
        synchronized (this) {
            r = findRecordLocked(userId, serviceInfo);
        }
        if (r == null && (flags & Context.BIND_AUTO_CREATE) != 0) {
            if (startServiceCommon(service, false, userId) == null) {
                return 0;
            }
            synchronized (this) {
                r = findRecordLocked(userId, serviceInfo);
            }
        }
        if (r == null) {
            return 0;
        }

        final ServiceRecord targetRecord = r;
        final ServiceRecord.IntentBindRecord boundRecord;
        boolean queuedWork = false;
        synchronized (this) {
            if (!containsServiceRecordLocked(targetRecord) || targetRecord.isRetired()) {
                return 0;
            }
            boundRecord = targetRecord.addToBoundIntent(new Intent(service), connection);
            if (boundRecord == null) {
                return 0;
            }
            if (boundRecord.isUnbindInFlight()) {
                // Wait for unbindFinished before choosing onRebind or a fresh onBind.
            } else if (boundRecord.hasPublishedBinder()) {
                if (boundRecord.consumeDoRebind()) {
                    queuedWork |= enqueueServiceOperation(targetRecord.process,
                            PendingServiceOperation.Type.REBIND,
                            "rebind " + ComponentUtils.toComponentName(serviceInfo),
                            () -> {
                                if (!isServiceDispatchValid(targetRecord.process, targetRecord)) {
                                    return;
                                }
                                targetRecord.process.client.scheduleBindService(
                                        targetRecord, boundRecord.getBindToken(),
                                        boundRecord.intent, true, 0,
                                        boundRecord.nextBindSequence());
                            }, reason -> boundRecord.setDoRebind(true));
                }
                final ComponentName componentName = ComponentUtils.toComponentName(serviceInfo);
                queuedWork |= enqueueServiceOperation(targetRecord.process,
                        PendingServiceOperation.Type.CONNECT,
                        "connect " + componentName,
                        () -> {
                            if (boundRecord.hasPublishedBinder()
                                    && boundRecord.containConnection(connection)) {
                                connectService(connection, componentName, boundRecord, false);
                            }
                        }, null);
            } else if (boundRecord.requestBindIfNeeded()) {
                queuedWork |= enqueueServiceOperation(targetRecord.process,
                        PendingServiceOperation.Type.BIND,
                        "bind " + ComponentUtils.toComponentName(serviceInfo),
                        () -> {
                            if (!isServiceDispatchValid(targetRecord.process, targetRecord)
                                    || !boundRecord.shouldDispatchBind()) {
                                return;
                            }
                            targetRecord.process.client.scheduleBindService(
                                    targetRecord, boundRecord.getBindToken(),
                                    boundRecord.intent, false, 0,
                                    boundRecord.nextBindSequence());
                        }, reason -> boundRecord.bindRequestFailed());
            }
            targetRecord.lastActivityTime = SystemClock.uptimeMillis();
        }
        if (queuedWork) {
            drainProcessLifecycle(targetRecord.process);
        }
        return 1;
    }


    @Override
    public boolean unbindService(IServiceConnection connection, int userId) {
        enforceCallerUserOrHost(userId);
        final ServiceRecord r;
        boolean queuedWork = false;
        synchronized (this) {
            r = findRecordLocked(connection);
            if (r == null) {
                return false;
            }

            for (ServiceRecord.IntentBindRecord bindRecord : r.bindings) {
                if (!bindRecord.containConnection(connection)) {
                    continue;
                }
                if (bindRecord.removeConnectionAndCheckIfLast(connection)) {
                    bindRecord.cancelPendingBindIfNoConnections();
                    if (bindRecord.beginUnbindIfNeeded()) {
                        queuedWork |= enqueueServiceOperation(r.process,
                                PendingServiceOperation.Type.UNBIND,
                                "unbind " + ComponentUtils.toComponentName(r.serviceInfo),
                                () -> r.process.client.scheduleUnbindService(
                                        r, bindRecord.getBindToken(), bindRecord.intent), null);
                    }
                }
            }

            if (r.startId <= 0 && r.getConnectionCount() <= 0) {
                r.retire();
                removeRecord(r);
                queuedWork |= enqueueStopOperationLocked(r, "last-client-unbound");
            }
        }
        if (queuedWork) {
            drainProcessLifecycle(r.process);
        }
        return true;
    }

    @Override
    public void unbindFinished(IBinder token, IBinder bindToken, Intent service,
                               boolean doRebind, int userId) {
        enforceCallerUserOrHost(userId);
        final ServiceRecord r;
        final ServiceRecord.IntentBindRecord boundRecord;
        boolean queuedWork = false;
        synchronized (this) {
            r = token instanceof ServiceRecord ? (ServiceRecord) token : null;
            if (r == null || !containsServiceRecordLocked(r) || r.isRetired()
                    || r.process.userId != userId) {
                return;
            }
            boundRecord = resolveBinding(r, bindToken, service);
            if (boundRecord == null) {
                return;
            }
            queuedWork = finishUnbindLocked(r, boundRecord, doRebind);
        }
        if (queuedWork) {
            drainProcessLifecycle(r.process);
        }
    }

    private ServiceRecord.IntentBindRecord resolveBinding(ServiceRecord service,
                                                           IBinder bindToken,
                                                           Intent intent) {
        if (bindToken != null) {
            return service.peekBinding(bindToken);
        }
        return intent != null ? service.peekBinding(intent) : null;
    }

    private boolean finishUnbindLocked(ServiceRecord service,
                                       ServiceRecord.IntentBindRecord binding,
                                       boolean doRebind) {
        boolean queuedWork = false;
        ServiceRecord.IntentBindRecord.UnbindResult result = binding.finishUnbind(doRebind);
        if (result == ServiceRecord.IntentBindRecord.UnbindResult.REBIND) {
            queuedWork |= enqueueServiceOperation(service.process,
                    PendingServiceOperation.Type.REBIND,
                    "rebind-finished " + ComponentUtils.toComponentName(service.serviceInfo),
                    () -> {
                        if (isServiceDispatchValid(service.process, service)
                                && binding.hasPublishedBinder()) {
                            service.process.client.scheduleBindService(
                                    service, binding.getBindToken(), binding.intent,
                                    true, 0, binding.nextBindSequence());
                        }
                    }, reason -> binding.setDoRebind(true));
            final ComponentName component = ComponentUtils.toComponentName(service.serviceInfo);
            for (IServiceConnection connection : binding.snapshotConnections()) {
                queuedWork |= enqueueServiceOperation(service.process,
                        PendingServiceOperation.Type.CONNECT,
                        "reconnect " + component,
                        () -> {
                            if (binding.hasPublishedBinder()
                                    && binding.containConnection(connection)) {
                                connectService(connection, component, binding, false);
                            }
                        }, null);
            }
        } else if (result == ServiceRecord.IntentBindRecord.UnbindResult.BIND) {
            queuedWork |= enqueueServiceOperation(service.process,
                    PendingServiceOperation.Type.BIND,
                    "bind-after-unbind " + ComponentUtils.toComponentName(service.serviceInfo),
                    () -> {
                        if (isServiceDispatchValid(service.process, service)
                                && binding.shouldDispatchBind()) {
                            service.process.client.scheduleBindService(
                                    service, binding.getBindToken(), binding.intent,
                                    false, 0, binding.nextBindSequence());
                        }
                    }, reason -> binding.bindRequestFailed());
        }
        return queuedWork;
    }


    @Override
    public boolean isVAServiceToken(IBinder token) {
        return token instanceof ServiceRecord;
    }


    @Override
    public void serviceDoneExecuting(IBinder token, int type, int startId, int res, int userId) {
        enforceCallerUserOrHost(userId);
        ServiceRecord r;
        boolean queuedWork = false;
        synchronized (this) {
            r = token instanceof ServiceRecord ? (ServiceRecord) token : null;
            if (r == null || r.process.userId != userId) {
                return;
            }
            if (ActivityManagerCompat.SERVICE_DONE_EXECUTING_STOP == type) {
                r.retire();
                removeRecord(r);
            } else if (ActivityManagerCompat.SERVICE_DONE_EXECUTING_UNBIND == type
                    && containsServiceRecordLocked(r) && !r.isRetired()) {
                ServiceRecord.IntentBindRecord binding = r.peekUnbindInFlight();
                if (binding != null) {
                    queuedWork = finishUnbindLocked(r, binding, false);
                }
            }
        }
        if (queuedWork) {
            drainProcessLifecycle(r.process);
        }
    }

    @Override
    public IBinder peekService(Intent service, String resolvedType, int userId) {
        enforceCallerUserOrHost(userId);
        synchronized (this) {
            ServiceInfo serviceInfo = resolveServiceInfo(service, userId);
            if (serviceInfo == null) {
                return null;
            }
            ServiceRecord r = findRecordLocked(userId, serviceInfo);
            if (r != null) {
                ServiceRecord.IntentBindRecord boundRecord = r.peekBinding(service);
                if (boundRecord != null) {
                    return boundRecord.binder;
                }
            }
            return null;
        }
    }

    @Override
    public void publishService(IBinder token, IBinder bindToken, Intent intent,
                               IBinder service, int userId) {
        enforceCallerUserOrHost(userId);
        final ServiceRecord r;
        final ServiceRecord.IntentBindRecord boundRecord;
        final List<IServiceConnection> connections;
        final ComponentName component;
        synchronized (this) {
            r = token instanceof ServiceRecord ? (ServiceRecord) token : null;
            if (r == null || !containsServiceRecordLocked(r) || r.isRetired()
                    || r.process.userId != userId) {
                return;
            }
            boundRecord = resolveBinding(r, bindToken, intent);
            if (boundRecord == null) {
                return;
            }
            component = ComponentUtils.toComponentName(r.serviceInfo);
            try {
                service = r.process.client.createProxyService(component, service);
            } catch (RemoteException e) {
                VLog.w(TAG, "Unable to create guest service proxy " + component, e);
                service = null;
            }
            connections = boundRecord.publish(r.generation, service);
        }
        for (IServiceConnection conn : connections) {
            if (boundRecord.containConnection(conn)) {
                connectService(conn, component, boundRecord, false);
            }
        }
    }

    private void connectService(IServiceConnection conn, ComponentName component, ServiceRecord.IntentBindRecord r,boolean dead) {
        try {
            BinderDelegateService delegateService = new BinderDelegateService(component, r.binder);
            ServiceConnectionCompat.connected(conn, component, delegateService, dead);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void notifyServiceDisconnected(IServiceConnection connection, ComponentName component) {
        try {
            ServiceConnectionCompat.connected(connection, component, null, true);
        } catch (RemoteException e) {
            VLog.w(TAG, "Unable to disconnect service client " + component, e);
        }
    }

    private boolean enqueueServiceOperation(ProcessRecord process,
            PendingServiceOperation.Type type, String description,
            PendingServiceOperation.DispatchAction action,
            PendingServiceOperation.CancellationListener cancellationListener) {
        return process.lifecycle.enqueue(new PendingServiceOperation(
                process.generation, type, description, action, cancellationListener));
    }

    private boolean enqueueStopOperationLocked(ServiceRecord record, String reason) {
        return enqueueServiceOperation(record.process, PendingServiceOperation.Type.STOP,
                "stop " + ComponentUtils.toComponentName(record.serviceInfo) + " reason=" + reason,
                () -> {
                    if (isProcessEndpointAlive(record.process)) {
                        record.process.client.scheduleStopService(record);
                    }
                }, null);
    }

    private void enqueueStopOperation(ServiceRecord record, String reason) {
        if (enqueueStopOperationLocked(record, reason)) {
            drainProcessLifecycle(record.process);
        }
    }

    /** CREATE_SERVICE is the bootstrap that causes HCallbackStub to bind the guest Application. */
    private boolean dispatchCreateService(ProcessRecord process, ServiceRecord record) {
        synchronized (this) {
            ProcessLifecycle.State state = process.lifecycle.state();
            if ((state != ProcessLifecycle.State.STARTING
                    && state != ProcessLifecycle.State.READY)
                    || !containsServiceRecordLocked(record) || record.isRetired()
                    || record.process != process || !isProcessEndpointActive(process)) {
                return false;
            }
        }
        try {
            process.client.scheduleCreateService(record, record.serviceInfo, 0);
            return true;
        } catch (RemoteException e) {
            VLog.e(TAG, "scheduleCreateService failed for "
                    + ComponentUtils.toComponentName(record.serviceInfo), e);
            return false;
        }
    }

    private boolean isServiceDispatchValid(ProcessRecord process, ServiceRecord record) {
        synchronized (this) {
            return process.lifecycle.state() == ProcessLifecycle.State.READY
                    && isProcessEndpointAlive(process)
                    && record.process == process
                    && !record.isRetired()
                    && containsServiceRecordLocked(record);
        }
    }

    private void drainProcessLifecycle(ProcessRecord process) {
        int dispatched = process.lifecycle.drain(process.generation);
        if (dispatched > 0) {
            VLog.i(TAG, "process-lifecycle-drain pid=" + process.pid
                    + " generation=" + process.generation
                    + " dispatched=" + dispatched
                    + " pending=" + process.lifecycle.pendingCount());
        }
        if (process.lifecycle.state() == ProcessLifecycle.State.FAILED
                && process.lifecycle.terminalReason()
                == ProcessLifecycle.TerminalReason.DISPATCH_FAILED) {
            VLog.e(TAG, "service-dispatch-failed pid=" + process.pid
                    + " operation=" + process.lifecycle.failedOperation());
            VLog.e(TAG, process.lifecycle.dispatchFailure());
            cleanupProcessGeneration(process, "service-dispatch-failed", true);
        }
    }

    private void scheduleStartupWatchdog(ProcessRecord process) {
        synchronized (this) {
            if (process.startupWatchdogScheduled
                    || process.lifecycle.state() != ProcessLifecycle.State.STARTING) {
                return;
            }
            process.startupWatchdogScheduled = true;
        }
        mServiceHandler.postDelayed(() -> {
            if (process.lifecycle.markStartupTimedOut(process.generation)) {
                VLog.e(TAG, "process-lifecycle-timeout pid=" + process.pid
                        + " generation=" + process.generation
                        + " pending=" + process.lifecycle.pendingCount());
                cleanupProcessGeneration(process, "application-startup-timeout", true);
            }
        }, SERVICE_STARTUP_TIMEOUT_MS);
    }

    private void failProcessGeneration(ProcessRecord process,
            ProcessLifecycle.TerminalReason reason, String detail) {
        if (process.lifecycle.markFailed(process.generation, reason)) {
            VLog.e(TAG, "process-lifecycle-failed pid=" + process.pid
                    + " generation=" + process.generation + " reason=" + detail);
            cleanupProcessGeneration(process, detail, true);
        }
    }

    private void cleanupProcessGeneration(ProcessRecord process, String reason,
            boolean terminateProcess) {
        final List<ServiceRecord> ownedServices = new ArrayList<>();
        final Map<ServiceRecord, List<IServiceConnection>> disconnectedClients =
                new IdentityHashMap<>();
        synchronized (this) {
            if (process.terminalCleanupStarted) {
                return;
            }
            process.terminalCleanupStarted = true;
            if (process.osIsolatedWorker) {
                mIsolatedServiceOwners.remove(process.isolatedOwnerKey,
                        process.generation, process);
                if (process.client instanceof IsolatedGuestClient) {
                    mIsolatedClients.remove((IsolatedGuestClient) process.client);
                }
            } else {
                mLogicalProcessOwners.remove(logicalKey(process), process.generation, process);
            }
            synchronized (mProcessNames) {
                if (mProcessNames.get(process.processName, process.vuid) == process) {
                    mProcessNames.remove(process.processName, process.vuid);
                }
                synchronized (mPidsSelfLocked) {
                    if (mPidsSelfLocked.get(process.pid) == process) {
                        mPidsSelfLocked.remove(process.pid);
                    }
                }
            }
            synchronized (mHistory) {
                Iterator<ServiceRecord> iterator = mHistory.iterator();
                while (iterator.hasNext()) {
                    ServiceRecord service = iterator.next();
                    if (service.process == process) {
                        List<IServiceConnection> connections = new ArrayList<>();
                        for (ServiceRecord.IntentBindRecord binding : service.bindings) {
                            connections.addAll(binding.snapshotConnections());
                        }
                        disconnectedClients.put(service, connections);
                        service.retire();
                        ownedServices.add(service);
                        iterator.remove();
                    }
                }
            }
        }

        mGmsBackgroundKeepAlive.release(process);
        mLinePushProcessGuard.release(process);
        for (ServiceRecord service : ownedServices) {
            ComponentName component = ComponentUtils.toComponentName(service.serviceInfo);
            for (IServiceConnection connection : disconnectedClients.get(service)) {
                notifyServiceDisconnected(connection, component);
            }
        }
        if (!process.osIsolatedWorker) {
            mMainStack.processDied(process);
        }
        VLog.w(TAG, "process-lifecycle-cleanup pid=" + process.pid
                + " generation=" + process.generation + " reason=" + reason
                + " services=" + ownedServices.size());
        if (process.osIsolatedWorker && process.client instanceof IsolatedGuestClient) {
            ((IsolatedGuestClient) process.client).close();
        } else if (terminateProcess && process.pid > 0) {
            killProcess(process.pid);
        }
    }

    private void onConnectionDied(ServiceRecord service,
            ServiceRecord.IntentBindRecord binding, IServiceConnection connection,
            boolean lastConnection) {
        if (!lastConnection) {
            return;
        }
        boolean queuedWork = false;
        synchronized (this) {
            if (!containsServiceRecordLocked(service) || service.isRetired()) {
                return;
            }
            binding.cancelPendingBindIfNoConnections();
            if (binding.beginUnbindIfNeeded()) {
                queuedWork |= enqueueServiceOperation(service.process,
                        PendingServiceOperation.Type.UNBIND,
                        "unbind-dead-client " + ComponentUtils.toComponentName(service.serviceInfo),
                        () -> service.process.client.scheduleUnbindService(
                                service, binding.getBindToken(), binding.intent), null);
            }
            if (service.startId <= 0 && service.getConnectionCount() <= 0) {
                service.retire();
                removeRecord(service);
                queuedWork |= enqueueStopOperationLocked(service, "connection-died");
            }
        }
        if (queuedWork) {
            drainProcessLifecycle(service.process);
        }
    }

    @Override
    public VParceledListSlice<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags, int userId) {
        enforceCallerUserOrHost(userId);
        synchronized (mHistory) {
            List<ActivityManager.RunningServiceInfo> services = new ArrayList<>(mHistory.size());
            for (ServiceRecord r : mHistory) {
                if (r.process.userId != userId) {
                    continue;
                }
                ActivityManager.RunningServiceInfo info = new ActivityManager.RunningServiceInfo();
                info.uid = r.process.vuid;
                info.pid = r.process.pid;
                ProcessRecord processRecord = findProcessLocked(r.process.pid);
                if (processRecord != null) {
                    info.process = processRecord.processName;
                    info.clientPackage = processRecord.info.packageName;
                }
                info.activeSince = r.activeSince;
                info.lastActivityTime = r.lastActivityTime;
                info.clientCount = r.getClientCount();
                info.service = ComponentUtils.toComponentName(r.serviceInfo);
                info.started = r.startId > 0;
                services.add(info);
            }
            return new VParceledListSlice<>(services);
        }
    }

    @Override
    public void setServiceForeground(ComponentName className, IBinder token, int id, Notification notification,
                                     boolean removeNotification, int userId) {
        enforceCallerUserOrHost(userId);
        final ServiceRecord r;
        final String packageName;
        int cancelId = 0;
        boolean shouldPost = false;
        synchronized (this) {
            r = token instanceof ServiceRecord ? (ServiceRecord) token : null;
            if (r == null || r.isRetired() || !containsServiceRecordLocked(r)
                    || !isCurrentProcessOwner(r.process)) {
                return;
            }
            packageName = r.serviceInfo.packageName;
            if (id != 0) {
                if (notification == null) {
                    throw new IllegalArgumentException("null notification");
                }
                if (r.foregroundId != id) {
                    if (r.foregroundId != 0) {
                        cancelId = r.foregroundId;
                    }
                    r.foregroundId = id;
                }
                r.foregroundNoti = notification;
                shouldPost = true;
            } else {
                if (removeNotification) {
                    cancelId = r.foregroundId;
                    r.foregroundId = 0;
                    r.foregroundNoti = null;
                }
            }
        }
        if (cancelId != 0) {
            cancelNotification(userId, cancelId, packageName);
        }
        if (shouldPost) {
            postNotification(userId, id, packageName, notification);
        }
    }

    private void cancelNotification(int userId, int id, String pkg) {
        id = VNotificationManager.get().dealNotificationId(id, pkg, null, userId);
        String tag = VNotificationManager.get().dealNotificationTag(id, pkg, null, userId);
        nm.cancel(tag, id);
    }

    private void postNotification(int userId, int id, String pkg, Notification notification) {
        id = VNotificationManager.get().dealNotificationId(id, pkg, null, userId);
        String tag = VNotificationManager.get().dealNotificationTag(id, pkg, null, userId);
//        VNotificationManager.get().dealNotification(id, notification, pkg);
        VNotificationManager.get().addNotification(id, tag, pkg, userId);
        try {
            nm.notify(tag, id, notification);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void processRestarted(String packageName, String processName, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord existing;
        synchronized (mPidsSelfLocked) {
            existing = findProcessLocked(callingPid);
        }
        if (!matchesRestartClaim(existing, packageName, processName, userId)) {
            // An untracked OS-recreated stub has no server-issued reservation generation. It must
            // not self-assign a package/user identity from caller-controlled AIDL arguments.
            VLog.e(TAG, "Rejecting unreserved process restart pid=" + callingPid);
            killProcess(callingPid);
        }
    }

    static boolean matchesRestartClaim(
            ProcessRecord record, String packageName, String processName, int userId) {
        return record != null && record.info != null && matchesRestartClaim(
                record.userId, record.info.packageName, record.processName,
                record.pkgList.contains(record.info.packageName),
                packageName, processName, userId);
    }

    static boolean matchesRestartClaim(
            int recordUserId, String recordPackage, String recordProcess, boolean ownsPackage,
            String requestedPackage, String requestedProcess, int requestedUserId) {
        return recordUserId == requestedUserId && ownsPackage
                && requestedPackage != null && requestedPackage.equals(recordPackage)
                && requestedProcess != null && requestedProcess.equals(recordProcess);
    }

    private int parseVPid(String stubProcessName) {
        String prefix = VirtualCore.get().getHostPkg() + ":p";
        if (stubProcessName != null && stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return -1;
    }


    private String getProcessName(int pid) {
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) {
            return null;
        }
        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if (info.pid == pid) {
                return info.processName;
            }
        }
        return null;
    }


    private ProcessRecord attachClient(int pid, final IBinder clientBinder,
            LogicalProcessOwnerRegistry.Reservation reservation,
            ProcessRecord expectedRecord) {
        final IVClient client = IVClient.Stub.asInterface(clientBinder);
        if (client == null) {
            killProcess(pid);
            return null;
        }
        IInterface thread = null;
        try {
            thread = ApplicationThreadCompat.asInterface(client.getAppThread());
        } catch (RemoteException e) {
            // process has dead
        }
        if (thread == null) {
            killProcess(pid);
            return null;
        }
        ProcessRecord app = null;
        try {
            IBinder token = client.getToken();
            if (token instanceof ProcessRecord) {
                app = (ProcessRecord) token;
            }
        } catch (RemoteException e) {
            // process has dead
        }
        if (app == null || (expectedRecord != null && app != expectedRecord)
                || app.vpid < 0 || app.vpid >= VASettings.STUB_COUNT
                || !isExpectedRunningStub(pid, app.vpid)) {
            killProcess(pid);
            return null;
        }

        LogicalProcessKey key = logicalKey(app);
        if (reservation != null && (!reservation.key().equals(key)
                || reservation.slot() != app.vpid
                || reservation.generation() != app.generation)) {
            mLogicalProcessOwners.cancel(reservation);
            killProcess(pid);
            return null;
        }

        IBinder previousClientBinder = app.client == null ? null : app.client.asBinder();
        int previousPid = app.pid;
        app.client = client;
        app.appThread = thread;
        app.pid = pid;

        boolean ownerAccepted;
        if (reservation != null) {
            LogicalProcessOwnerRegistry.ClaimResult<ProcessRecord> claim =
                    mLogicalProcessOwners.claim(reservation, app);
            ownerAccepted = claim.status() == LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED
                    || claim.status()
                    == LogicalProcessOwnerRegistry.ClaimStatus.ALREADY_OWNED;
            if (!ownerAccepted) {
                VLog.e(TAG, "Rejecting process owner claim key=" + key + " pid=" + pid
                        + " generation=" + app.generation + " status=" + claim.status());
            }
        } else {
            LogicalProcessOwnerRegistry.ReconcileResult<ProcessRecord> reconciliation =
                    mLogicalProcessOwners.reconcile(key, app.vpid, app.generation, app);
            ownerAccepted = reconciliation.status()
                    == LogicalProcessOwnerRegistry.ReconcileStatus.RECONCILED
                    || reconciliation.status()
                    == LogicalProcessOwnerRegistry.ReconcileStatus.ALREADY_OWNED;
            if (!ownerAccepted) {
                VLog.e(TAG, "Rejecting reconciled process owner key=" + key + " pid=" + pid
                        + " generation=" + app.generation
                        + " status=" + reconciliation.status());
            }
        }
        if (!ownerAccepted) {
            app.client = null;
            app.appThread = null;
            app.pid = previousPid;
            killProcess(pid);
            return null;
        }

        synchronized (mProcessNames) {
            mProcessNames.put(app.processName, app.vuid, app);
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.put(app.pid, app);
            }
        }
        mGmsBackgroundKeepAlive.retain(app);

        boolean needsDeathLink = previousClientBinder == null
                || !previousClientBinder.equals(clientBinder) || previousPid != pid;
        if (!needsDeathLink) {
            return app;
        }
        try {
            final ProcessRecord record = app;
            clientBinder.linkToDeath(new DeathRecipient() {
                @Override
                public void binderDied() {
                    clientBinder.unlinkToDeath(this, 0);
                    onProcessDead(record);
                }
            }, 0);
        } catch (RemoteException e) {
            onProcessDead(app);
            return null;
        }
        return app;
    }

    private void onProcessDead(ProcessRecord record) {
        if (!record.lifecycle.markDead(record.generation,
                ProcessLifecycle.TerminalReason.PROCESS_DIED)) {
            return;
        }
        cleanupProcessGeneration(record, "process-died", false);
    }

    @Override
    public int getFreeStubCount() {
        synchronized (mPidsSelfLocked) {
            return VASettings.STUB_COUNT - mPidsSelfLocked.size();
        }
    }

    @Override
    public int initProcess(String packageName, String processName, int userId) {
        enforceCallerUserOrHost(userId);
        ProcessRecord r = startProcessIfNeedLocked(processName, userId, packageName);
        return r != null ? r.vpid : -1;
    }

    ProcessRecord startProcessIfNeedLocked(String processName, int userId, String packageName) {
        return startProcessIfNeedLocked(processName, userId, packageName, false);
    }

    private ProcessRecord startProcessIfNeedLocked(String processName, int userId,
                                                    String packageName,
                                                    boolean isolatedProcess) {
        mProcessStartGate.enter();
        try {
            return startProcessWithGateHeld(processName, userId, packageName, isolatedProcess);
        } finally {
            mProcessStartGate.exit();
        }
    }

    private ProcessRecord tryStartProcessForBinding(String processName, int userId,
                                                     String packageName,
                                                     boolean isolatedProcess) {
        ProcessRecord existing = findLiveLogicalProcess(processName, userId, packageName);
        if (existing != null) {
            return existing;
        }
        if (!mProcessStartGate.tryEnter()) {
            // The process-start owner may have claimed the logical process between the first
            // lookup and this failed tryLock. Reuse that STARTING endpoint so the service work is
            // queued behind application bind instead of dropping the one-shot bind request.
            existing = findLiveLogicalProcess(processName, userId, packageName);
            if (existing != null) {
                VLog.d(TAG, "Reusing contended service process package=" + packageName
                        + " process=" + processName + " user=" + userId);
                return existing;
            }
            VLog.d(TAG, "Skipping contended service process start package=" + packageName
                    + " process=" + processName + " user=" + userId);
            return null;
        }
        try {
            return startProcessWithGateHeld(processName, userId, packageName, isolatedProcess);
        } finally {
            mProcessStartGate.exit();
        }
    }

    private ProcessRecord findLiveLogicalProcess(String processName, int userId,
                                                  String packageName) {
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        if (setting == null) {
            return null;
        }
        int vuid = VUserHandle.getUid(userId, setting.appId);
        LogicalProcessOwnerRegistry.OwnerSnapshot<ProcessRecord> existing =
                mLogicalProcessOwners.findLive(
                        new LogicalProcessKey(vuid, packageName, processName));
        return existing == null ? null : existing.owner();
    }

    private ProcessRecord startIsolatedServiceProcess(ServiceInfo serviceInfo, int userId) {
        PackageSetting setting = PackageCacheManager.getSetting(serviceInfo.packageName);
        ApplicationInfo info = VPackageManagerService.get().getApplicationInfo(
                serviceInfo.packageName, 0, userId);
        if (setting == null || info == null) {
            return null;
        }
        int vuid = VUserHandle.getUid(userId, setting.appId);
        LogicalProcessKey key = isolatedServiceKey(vuid, serviceInfo);
        synchronized (this) {
            LogicalProcessOwnerRegistry.OwnerSnapshot<ProcessRecord> existing =
                    mIsolatedServiceOwners.find(key);
            if (existing != null && isIsolatedOwnerAlive(existing.owner())) {
                return existing.owner();
            }
            LogicalProcessOwnerRegistry.ReservationResult<ProcessRecord> reserved = null;
            for (int slot = 0;
                    slot < com.lody.virtual.client.isolated.IsolatedWorkerSlots.SLOT_COUNT;
                    slot++) {
                LogicalProcessOwnerRegistry.ReservationResult<ProcessRecord> candidate =
                        mIsolatedServiceOwners.reserve(key, slot);
                if (candidate.status()
                        == LogicalProcessOwnerRegistry.ReservationStatus.EXISTING_OWNER) {
                    return candidate.existingOwner().owner();
                }
                if (candidate.status()
                        == LogicalProcessOwnerRegistry.ReservationStatus.RESERVED) {
                    reserved = candidate;
                    break;
                }
            }
            if (reserved == null) {
                VLog.e(TAG, "No real isolated worker slot available for " + key);
                return null;
            }
            LogicalProcessOwnerRegistry.Reservation reservation = reserved.reservation();
            ProcessRecord process = new ProcessRecord(info, serviceInfo.processName, vuid,
                    reservation.slot(), reservation.generation());
            process.osIsolatedWorker = true;
            process.isolatedOwnerKey = key;
            process.pkgList.add(info.packageName);
            IsolatedGuestClient client = new IsolatedGuestClient(
                    VirtualCore.get().getContext(), reservation.slot(), userId, this);
            process.client = client;
            process.appThread = client;
            LogicalProcessOwnerRegistry.ClaimResult<ProcessRecord> claim =
                    mIsolatedServiceOwners.claim(reservation, process);
            if (claim.status() != LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED
                    && claim.status()
                    != LogicalProcessOwnerRegistry.ClaimStatus.ALREADY_OWNED) {
                mIsolatedServiceOwners.cancel(reservation);
                return null;
            }
            mIsolatedClients.put(client, process);
            if (!client.start()) {
                mIsolatedClients.remove(client);
                mIsolatedServiceOwners.remove(key, process.generation, process);
                return null;
            }
            VLog.i(TAG, "isolated-worker-reserved key=" + key
                    + " slot=" + reservation.slot()
                    + " generation=" + reservation.generation());
            return process;
        }
    }

    private ProcessRecord startProcessWithGateHeld(String processName, int userId,
                                                    String packageName,
                                                    boolean isolatedProcess) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        ApplicationInfo info = VPackageManagerService.get().getApplicationInfo(
                packageName, 0, userId);
        if (ps == null || info == null) {
            return null;
        }
        if (!ps.isLaunched(userId)) {
            sendFirstLaunchBroadcast(ps, userId);
            ps.setLaunched(userId, true);
            VAppManagerService.get().savePersistenceData();
        }
        int uid = VUserHandle.getUid(userId, ps.appId);
        LogicalProcessKey key = new LogicalProcessKey(uid, packageName, processName);
        reconcileRunningStubProcessesLocked();
        LogicalProcessOwnerRegistry.OwnerSnapshot<ProcessRecord> existing =
                mLogicalProcessOwners.find(key);
        if (existing != null && isLogicalOwnerAlive(existing.owner())) {
            existing.owner().pkgList.add(info.packageName);
            return existing.owner();
        }

        LogicalProcessOwnerRegistry.ReservationResult<ProcessRecord> reserved =
                reserveFreeStubProcessLocked(key);
        if (reserved == null || reserved.status()
                != LogicalProcessOwnerRegistry.ReservationStatus.RESERVED) {
            return null;
        }
        int vpid = reserved.reservation().slot();
        int reportedUidOverride = IsolatedProcessUidPolicy.reportedUidOverride(
                uid, isolatedProcess, Process.myUid());
        if (isolatedProcess) {
            VLog.i(TAG, "starting isolated guest process=" + processName
                    + " package=" + packageName + " user=" + userId
                    + " reportedUid=" + reportedUidOverride);
        }
        ProcessRecord app = performStartProcessLocked(uid, vpid, info, processName,
                reportedUidOverride, reserved.reservation());
        if (app != null) {
            app.pkgList.add(info.packageName);
        }
        return app;
    }

    private void sendFirstLaunchBroadcast(PackageSetting ps, int userId) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FIRST_LAUNCH, Uri.fromParts("package", ps.packageName, null));
        intent.setPackage(ps.packageName);
        intent.putExtra(Intent.EXTRA_UID, VUserHandle.getUid(ps.appId, userId));
        intent.putExtra("android.intent.extra.user_handle", userId);
        sendBroadcastAsUser(intent, null);
    }


    @Override
    public int getUidByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return r.vuid;
            }
        }
        return resolveUntrackedProcessUid(pid, Process.myPid(), Process.myUid(),
                RawSystemProcessAuthority.isExactHostMainProcess(
                        pid, Process.myUid(), VirtualCore.get().getHostPkg()));
    }

    static int resolveUntrackedProcessUid(
            int pid, int enginePid, int hostUid, boolean exactSystemHostProcess) {
        // Only the engine itself and a host main process authenticated by Android's system process
        // record are host principals. Stub/guest, stale, forked and unknown callers fail closed.
        if (pid == enginePid || exactSystemHostProcess) {
            return hostUid;
        }
        return -1;
    }

    private ProcessRecord performStartProcessLocked(int vuid, int vpid, ApplicationInfo info,
            String processName, LogicalProcessOwnerRegistry.Reservation reservation) {
        return performStartProcessLocked(vuid, vpid, info, processName,
                IsolatedProcessUidPolicy.reportedUidOverride(vuid, false, Process.myUid()), reservation);
    }

    private ProcessRecord performStartProcessLocked(int vuid, int vpid, ApplicationInfo info,
            String processName, int reportedUidOverride,
            LogicalProcessOwnerRegistry.Reservation reservation) {
        ProcessRecord app = new ProcessRecord(info, processName, vuid, vpid,
                reservation.generation(), reportedUidOverride);
        Bundle extras = new Bundle();
        BundleCompat.putBinder(extras, StubProcessContract.KEY_SERVER_TOKEN, app);
        extras.putInt(StubProcessContract.KEY_VUID, vuid);
        extras.putString(StubProcessContract.KEY_PROCESS_NAME, processName);
        extras.putString(StubProcessContract.KEY_PACKAGE_NAME, info.packageName);
        extras.putLong(StubProcessContract.KEY_GENERATION, reservation.generation());
        extras.putInt(StubProcessContract.KEY_REPORTED_UID_OVERRIDE, reportedUidOverride);
        Bundle res = callStubProcessInitWithRetry(vpid, reservation, app, extras);
        if (res == null || !res.getBoolean(StubProcessContract.KEY_ACCEPTED, false)) {
            mLogicalProcessOwners.cancel(reservation);
            VLog.e(TAG, "Stub rejected process owner key=" + reservation.key()
                    + " slot=" + vpid + " reason="
                    + (res == null ? "null-response"
                    : res.getString(StubProcessContract.KEY_REASON)));
            return null;
        }
        int pid = res.getInt(StubProcessContract.KEY_PID, -1);
        if (!responseMatchesReservation(res, reservation, app, pid)) {
            mLogicalProcessOwners.cancel(reservation);
            if (pid > 0 && isExpectedRunningStub(pid, vpid)) {
                killProcess(pid);
            }
            return null;
        }
        IBinder clientBinder = BundleCompat.getBinder(res, StubProcessContract.KEY_CLIENT);
        ProcessRecord attached = attachClient(pid, clientBinder, reservation, app);
        if (attached == null) {
            mLogicalProcessOwners.cancel(reservation);
        }
        return attached;
    }

    /**
     * A stale Stub killed during engine reconciliation can remain addressable briefly while
     * ActivityManager tears down its provider. Retrying the same record token and generation is
     * safe because StubProcessOwner treats that exact claim as idempotent.
     */
    private Bundle callStubProcessInitWithRetry(int vpid,
            LogicalProcessOwnerRegistry.Reservation reservation, ProcessRecord app,
            Bundle extras) {
        Bundle response = null;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= STUB_INIT_MAX_ATTEMPTS; attempt++) {
            ContentProviderClient providerClient = null;
            try {
                // The Stub provider is only a bootstrap transport. Keeping this client open
                // creates a stable-provider dependency from the engine to the guest process;
                // when a short-lived guest subprocess exits normally, Android then kills the
                // engine as a dependent process. Use the provider only for the synchronous init
                // exchange and close it before returning; the IVClient Binder and explicit
                // process lifecycle own the guest after the handshake.
                if (StubProviderBootstrapPolicy.useStableProviderDependency()) {
                    providerClient = VirtualCore.get().getContext().getContentResolver()
                            .acquireContentProviderClient(VASettings.getStubAuthority(vpid));
                } else {
                    providerClient = VirtualCore.get().getContext().getContentResolver()
                            .acquireUnstableContentProviderClient(VASettings.getStubAuthority(vpid));
                }
                response = providerClient == null ? null : providerClient.call(
                        StubProcessContract.METHOD_INIT_PROCESS, null, extras);
                lastFailure = null;
            } catch (RemoteException | RuntimeException initFailure) {
                lastFailure = initFailure;
                response = null;
            }
            if (response != null
                    && response.getBoolean(StubProcessContract.KEY_ACCEPTED, false)) {
                if (!StubProviderBootstrapPolicy.retainProviderAfterHandshake()) {
                    closeQuietly(providerClient);
                }
                return response;
            }
            closeQuietly(providerClient);
            if (response != null) {
                int conflictingPid = response.getInt(StubProcessContract.KEY_PID, -1);
                IBinder conflictingToken = BundleCompat.getBinder(response,
                        StubProcessContract.KEY_SERVER_TOKEN);
                if (conflictingPid > 0 && conflictingToken != app
                        && isExpectedRunningStub(conflictingPid, vpid)) {
                    VLog.w(TAG, "Terminating conflicting Stub claim key=" + reservation.key()
                            + " slot=" + vpid + " pid=" + conflictingPid + " reason="
                            + response.getString(StubProcessContract.KEY_REASON));
                    killProcess(conflictingPid);
                }
            }
            if (attempt < STUB_INIT_MAX_ATTEMPTS) {
                SystemClock.sleep(STUB_INIT_RETRY_DELAY_MS);
            }
        }
        if (lastFailure != null) {
            VLog.e(TAG, "Unable to initialize Stub owner key=" + reservation.key()
                    + " slot=" + vpid + " error=" + lastFailure);
        }
        return response;
    }

    private static void closeQuietly(ContentProviderClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // The provider process may already be dead during terminal cleanup.
        }
    }

    private LogicalProcessOwnerRegistry.ReservationResult<ProcessRecord>
            reserveFreeStubProcessLocked(LogicalProcessKey key) {
        for (int vpid = 0; vpid < VASettings.STUB_COUNT; vpid++) {
            LogicalProcessOwnerRegistry.ReservationResult<ProcessRecord> result =
                    mLogicalProcessOwners.reserve(key, vpid);
            if (result.status() == LogicalProcessOwnerRegistry.ReservationStatus.RESERVED
                    || result.status()
                    == LogicalProcessOwnerRegistry.ReservationStatus.EXISTING_OWNER) {
                return result;
            }
        }
        return null;
    }

    /**
     * Rebuilds server bookkeeping only from Stub processes that ActivityManager already reports.
     * Provider authorities for absent slots are deliberately never queried because doing so would
     * start an empty Stub as a side effect of reconciliation.
     */
    private void reconcileRunningStubProcessesLocked() {
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) {
            return;
        }
        List<ActivityManager.RunningAppProcessInfo> snapshot = new ArrayList<>(processes);
        for (ActivityManager.RunningAppProcessInfo running : snapshot) {
            int vpid = parseVPid(running.processName);
            if (vpid < 0 || vpid >= VASettings.STUB_COUNT || running.uid != Process.myUid()) {
                continue;
            }
            if (!isExpectedRunningStub(running.pid, vpid)) {
                continue;
            }

            Bundle response;
            try {
                response = ProviderCall.call(VASettings.getStubAuthority(vpid),
                        StubProcessContract.METHOD_QUERY_OWNER, null, null);
            } catch (RuntimeException queryFailure) {
                VLog.w(TAG, "Unable to query running Stub owner slot=" + vpid
                        + " pid=" + running.pid + " error=" + queryFailure);
                continue;
            }
            int queriedPid = response == null ? -1
                    : response.getInt(StubProcessContract.KEY_PID, -1);
            if (response == null
                    || !response.getBoolean(StubProcessContract.KEY_ACCEPTED, false)
                    || queriedPid != running.pid
                    || !isExpectedRunningStub(queriedPid, vpid)) {
                VLog.e(TAG, "Rejecting Stub owner query slot=" + vpid
                        + " runningPid=" + running.pid + " queriedPid=" + queriedPid);
                continue;
            }
            if (!response.getBoolean(StubProcessContract.KEY_HAS_OWNER, false)) {
                continue;
            }

            IBinder serverToken = BundleCompat.getBinder(response,
                    StubProcessContract.KEY_SERVER_TOKEN);
            if (!(serverToken instanceof ProcessRecord)) {
                VLog.e(TAG, "Killing unrecoverable Stub owner slot=" + vpid
                        + " pid=" + queriedPid + " reason=foreign-server-token");
                killProcess(queriedPid);
                continue;
            }
            ProcessRecord record = (ProcessRecord) serverToken;
            if (!responseMatchesRecord(response, record, vpid)) {
                VLog.e(TAG, "Killing inconsistent Stub owner slot=" + vpid
                        + " pid=" + queriedPid);
                killProcess(queriedPid);
                continue;
            }
            IBinder clientBinder = BundleCompat.getBinder(response,
                    StubProcessContract.KEY_CLIENT);
            attachClient(queriedPid, clientBinder, null, record);
        }
    }

    private boolean responseMatchesReservation(Bundle response,
            LogicalProcessOwnerRegistry.Reservation reservation, ProcessRecord record, int pid) {
        if (!response.getBoolean(StubProcessContract.KEY_HAS_OWNER, false)
                || pid <= 0 || !isExpectedRunningStub(pid, reservation.slot())
                || response.getInt(StubProcessContract.KEY_VUID, -1)
                != reservation.key().vuid()
                || !reservation.key().packageName().equals(
                response.getString(StubProcessContract.KEY_PACKAGE_NAME))
                || !reservation.key().processName().equals(
                response.getString(StubProcessContract.KEY_PROCESS_NAME))
                || response.getLong(StubProcessContract.KEY_GENERATION, -1)
                != reservation.generation()
                || response.getInt(StubProcessContract.KEY_REPORTED_UID_OVERRIDE,
                IsolatedProcessUidPolicy.NO_OVERRIDE) != record.reportedUidOverride) {
            VLog.e(TAG, "Rejecting inconsistent Stub init response key=" + reservation.key()
                    + " slot=" + reservation.slot() + " pid=" + pid);
            return false;
        }
        IBinder serverToken = BundleCompat.getBinder(response,
                StubProcessContract.KEY_SERVER_TOKEN);
        if (serverToken != record) {
            VLog.e(TAG, "Rejecting Stub init token mismatch key=" + reservation.key()
                    + " slot=" + reservation.slot() + " pid=" + pid);
            return false;
        }
        return true;
    }

    private boolean responseMatchesRecord(Bundle response, ProcessRecord record, int vpid) {
        if (record.info == null || record.info.packageName == null) {
            return false;
        }
        return record.vpid == vpid
                && record.vuid == response.getInt(StubProcessContract.KEY_VUID, -1)
                && record.info.packageName.equals(
                response.getString(StubProcessContract.KEY_PACKAGE_NAME))
                && record.processName.equals(
                response.getString(StubProcessContract.KEY_PROCESS_NAME))
                && record.generation
                == response.getLong(StubProcessContract.KEY_GENERATION, -1)
                && record.reportedUidOverride == response.getInt(
                StubProcessContract.KEY_REPORTED_UID_OVERRIDE,
                IsolatedProcessUidPolicy.NO_OVERRIDE);
    }

    private static boolean isIsolatedProcess(ServiceInfo serviceInfo) {
        return serviceInfo != null
                && (serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
    }

    private boolean isExpectedRunningStub(int pid, int vpid) {
        if (pid <= 0 || vpid < 0 || vpid >= VASettings.STUB_COUNT) {
            return false;
        }
        String expectedName = VirtualCore.get().getHostPkg() + ":p" + vpid;
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == pid && process.uid == Process.myUid()
                    && expectedName.equals(process.processName)) {
                return true;
            }
        }
        return false;
    }

    private static LogicalProcessKey logicalKey(ProcessRecord record) {
        return new LogicalProcessKey(record.vuid, record.info.packageName, record.processName);
    }

    private static LogicalProcessKey isolatedServiceKey(int vuid, ServiceInfo serviceInfo) {
        return new LogicalProcessKey(vuid, serviceInfo.packageName,
                serviceInfo.processName + "#" + serviceInfo.name);
    }

    private boolean isCurrentProcessOwner(ProcessRecord record) {
        if (record.osIsolatedWorker) {
            LogicalProcessOwnerRegistry.OwnerSnapshot<ProcessRecord> owner =
                    mIsolatedServiceOwners.find(record.isolatedOwnerKey);
            return owner != null && owner.owner() == record
                    && owner.generation() == record.generation;
        }
        return mProcessNames.get(record.processName, record.vuid) == record;
    }

    private static boolean isProcessEndpointAlive(ProcessRecord process) {
        if (process == null || process.appThread == null) {
            return false;
        }
        if (process.osIsolatedWorker) {
            return process.client instanceof IsolatedGuestClient
                    && ((IsolatedGuestClient) process.client).isWorkerAlive();
        }
        return process.appThread.asBinder().isBinderAlive();
    }

    private static boolean isProcessEndpointActive(ProcessRecord process) {
        if (process != null && process.osIsolatedWorker
                && process.client instanceof IsolatedGuestClient) {
            return ((IsolatedGuestClient) process.client).isEndpointActive();
        }
        return isProcessEndpointAlive(process);
    }

    private static boolean isLogicalOwnerAlive(ProcessRecord record) {
        if (record == null || record.terminalCleanupStarted || record.client == null) {
            return false;
        }
        ProcessLifecycle.State state = record.lifecycle.state();
        IBinder binder = record.client.asBinder();
        return (state == ProcessLifecycle.State.STARTING || state == ProcessLifecycle.State.READY)
                && binder != null && binder.isBinderAlive() && binder.pingBinder();
    }

    private static boolean isIsolatedOwnerAlive(ProcessRecord record) {
        if (record == null || record.terminalCleanupStarted
                || !(record.client instanceof IsolatedGuestClient)) {
            return false;
        }
        ProcessLifecycle.State state = record.lifecycle.state();
        return (state == ProcessLifecycle.State.STARTING || state == ProcessLifecycle.State.READY)
                && ((IsolatedGuestClient) record.client).isEndpointActive();
    }

    @Override
    public void onReady(IsolatedGuestClient client, int pid, int uid) {
        ProcessRecord process;
        synchronized (this) {
            process = mIsolatedClients.get(client);
            if (process == null || process.client != client || process.terminalCleanupStarted) {
                return;
            }
            process.pid = pid;
            process.physicalUid = uid;
        }
        if (process.lifecycle.markReady(process.generation)) {
            VLog.i(TAG, "isolated-worker-ready key=" + process.isolatedOwnerKey
                    + " slot=" + process.vpid + " pid=" + pid + " uid=" + uid
                    + " generation=" + process.generation);
            drainProcessLifecycle(process);
        }
    }

    @Override
    public void onCreateFailed(IsolatedGuestClient client, String detail) {
        ProcessRecord process;
        synchronized (this) {
            process = mIsolatedClients.get(client);
        }
        if (process != null) {
            VLog.e(TAG, "isolated-worker-create-failed key=" + process.isolatedOwnerKey
                    + " detail=" + detail);
            failProcessGeneration(process,
                    ProcessLifecycle.TerminalReason.APPLICATION_BIND_FAILED,
                    "isolated-service-create-failed");
        }
    }

    @Override
    public void onWorkerDied(IsolatedGuestClient client) {
        ProcessRecord process;
        synchronized (this) {
            process = mIsolatedClients.get(client);
        }
        if (process != null && process.lifecycle.markDead(process.generation,
                ProcessLifecycle.TerminalReason.PROCESS_DIED)) {
            cleanupProcessGeneration(process, "isolated-worker-died", false);
        }
    }

    @Override
    public void onServicePublished(IBinder token, IBinder bindToken, Intent intent,
            IBinder service, int userId) {
        publishService(token, bindToken, intent, service, userId);
    }

    @Override
    public void onServiceUnbound(IBinder token, IBinder bindToken, Intent intent,
            boolean doRebind, int userId) {
        unbindFinished(token, bindToken, intent, doRebind, userId);
    }

    @Override
    public void onServiceStopped(IBinder token, int userId) {
        enforceCallerUserOrHost(userId);
        ProcessRecord process = null;
        if (token instanceof ServiceRecord) {
            process = ((ServiceRecord) token).process;
        }
        serviceDoneExecuting(token, ActivityManagerCompat.SERVICE_DONE_EXECUTING_STOP,
                0, 0, userId);
        if (process != null && process.osIsolatedWorker
                && process.lifecycle.markDead(process.generation,
                ProcessLifecycle.TerminalReason.PROCESS_DIED)) {
            cleanupProcessGeneration(process, "isolated-service-stopped", false);
        }
    }

    @Override
    public boolean isAppProcess(String processName) {
        return parseVPid(processName) != -1;
    }

    @Override
    public boolean isAppPid(int pid) {
        synchronized (mPidsSelfLocked) {
            ProcessRecord record = findProcessLocked(pid);
            return record != null && canCallerObserveUser(record.userId);
        }
    }

    @Override
    public String getAppProcessName(int pid) {
        synchronized (mPidsSelfLocked) {
            ProcessRecord r = mPidsSelfLocked.get(pid);
            if (r != null && canCallerObserveUser(r.userId)) {
                return r.processName;
            }
        }
        return null;
    }

    @Override
    public List<String> getProcessPkgList(int pid) {
        synchronized (mPidsSelfLocked) {
            ProcessRecord r = mPidsSelfLocked.get(pid);
            if (r != null && canCallerObserveUser(r.userId)) {
                return new ArrayList<>(r.pkgList);
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void killAllApps() {
        com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        synchronized (mPidsSelfLocked) {
            for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                ProcessRecord r = mPidsSelfLocked.valueAt(i);
                killProcess(r.pid);
            }
        }
    }

    @Override
    public void killAppByPkg(final String pkg, int userId) {
        if (userId == VUserHandle.USER_ALL) {
            com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
        } else {
            enforceCallerUserOrHost(userId);
        }
        synchronized (mProcessNames) {
            ArrayMap<String, SparseArray<ProcessRecord>> map = mProcessNames.getMap();
            int N = map.size();
            while (N-- > 0) {
                SparseArray<ProcessRecord> uids = map.valueAt(N);
                for (int i = 0; i < uids.size(); i++) {
                    ProcessRecord r = uids.valueAt(i);
                    if (userId != VUserHandle.USER_ALL) {
                        if (r.userId != userId) {
                            continue;
                        }
                    }
                    if (r.pkgList.contains(pkg)) {
                        killProcess(r.pid);
                    }
                }
            }
        }
    }

    @Override
    public boolean isAppRunning(String packageName, int userId) {
        enforceCallerUserOrHost(userId);
        boolean running = false;
        synchronized (mPidsSelfLocked) {
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.valueAt(N);
                if (r.userId == userId && r.info.packageName.equals(packageName)) {
                    running = true;
                    break;
                }
            }
            return running;
        }
    }

    @Override
    public void killApplicationProcess(final String processName, int uid) {
        enforceCallerUserOrHost(VUserHandle.getUserId(uid));
        synchronized (mProcessNames) {
            ProcessRecord r = mProcessNames.get(processName, uid);
            if (r != null) {
                killProcess(r.pid);
            }
        }
    }

    @Override
    public void dump() {

    }

    @Override
    public void registerProcessObserver(IProcessObserver observer) {

    }

    @Override
    public void unregisterProcessObserver(IProcessObserver observer) {

    }

    @Override
    public String getInitialPackage(int pid) {
        synchronized (mPidsSelfLocked) {
            ProcessRecord r = mPidsSelfLocked.get(pid);
            if (r != null && canCallerObserveUser(r.userId)) {
                return r.info.packageName;
            }
            return null;
        }
    }

    @Override
    public void handleApplicationCrash() {
        // Nothing
    }

    private boolean canCallerObserveUser(int targetUserId) {
        int callingPid = Binder.getCallingPid();
        if (callingPid == Process.myPid()) return true;
        synchronized (mPidsSelfLocked) {
            ProcessRecord caller = findProcessLocked(callingPid);
            if (caller != null) return caller.userId == targetUserId;
        }
        // Query the raw system ActivityManager captured before hooks. The OS process record is not
        // affected by a guest changing argv[0], and lookup failure is deliberately fail-closed.
        return RawSystemProcessAuthority.isExactHostMainProcess(
                callingPid, Process.myUid(), VirtualCore.get().getHostPkg());
    }

    static boolean canObserveUser(int callingVuid, int hostUid, int targetUserId) {
        return callingVuid == hostUid || (callingVuid >= 0 && targetUserId >= 0
                && VUserHandle.getUserId(callingVuid) == targetUserId);
    }

    @Override
    public void appDoneExecuting(IBinder processToken, boolean success) {
        final ProcessRecord r;
        synchronized (mPidsSelfLocked) {
            r = mPidsSelfLocked.get(VBinder.getCallingPid());
        }
        if (r == null || processToken != r) {
            VLog.w(TAG, "Ignoring stale appDoneExecuting pid=" + VBinder.getCallingPid()
                    + " token=" + processToken);
            return;
        }
        if (!success) {
            failProcessGeneration(r,
                    ProcessLifecycle.TerminalReason.APPLICATION_BIND_FAILED,
                    "application-bind-failed");
            return;
        }
        if (r.lifecycle.markReady(r.generation)) {
            mGmsBackgroundKeepAlive.retain(r);
            VLog.i(TAG, "process-lifecycle pid=" + r.pid + " generation=" + r.generation
                    + " STARTING->READY pending=" + r.lifecycle.pendingCount());
            drainProcessLifecycle(r);
        }
    }


    /**
     * Should guard by {@link VActivityManagerService#mPidsSelfLocked}
     *
     * @param pid pid
     */
    public ProcessRecord findProcessLocked(int pid) {
        return mPidsSelfLocked.get(pid);
    }

    /**
     * Should guard by {@link VActivityManagerService#mProcessNames}
     *
     * @param uid vuid
     */
    public ProcessRecord findProcessLocked(String processName, int uid) {
        return mProcessNames.get(processName, uid);
    }

    public int stopUser(int userHandle, IStopUserCallback.Stub stub) {
        retireUserProcesses(userHandle, "user-stop");
        mPendingIntents.clearUser(userHandle);
        if (hasUserRuntimeState(userHandle)) return -1;
        try {
            stub.userStopped(userHandle);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void sendOrderedBroadcastAsUser(Intent intent, VUserHandle user, String receiverPermission,
                                           BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
                                           String initialData, Bundle initialExtras) {
        Context context = VirtualCore.get().getContext();
        if (user != null) {
            intent.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        // TODO: checkPermission
        context.sendOrderedBroadcast(intent, null/* permission */, resultReceiver, scheduler, initialCode, initialData,
                initialExtras);
    }

    public void sendBroadcastAsUser(Intent intent, VUserHandle user) {
        SpecialComponentList.protectIntent(intent);
        Context context = VirtualCore.get().getContext();
        if (user != null) {
            intent.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        context.sendBroadcast(intent);
    }

    public boolean bindServiceAsUser(Intent service, ServiceConnection connection, int flags, VUserHandle user) {
        service = new Intent(service);
        if (user != null) {
            service.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        return VirtualCore.get().getContext().bindService(service, connection, flags);
    }

    public void sendBroadcastAsUser(Intent intent, VUserHandle user, String permission) {
        SpecialComponentList.protectIntent(intent);
        Context context = VirtualCore.get().getContext();
        if (user != null) {
            intent.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        // TODO: checkPermission
        context.sendBroadcast(intent);
    }

    boolean handleStaticBroadcast(int appId, ActivityInfo info, Intent intent,
                                  PendingResultData result) {
        Intent realIntent = intent.getParcelableExtra("_VA_|_intent_");
        ComponentName component = intent.getParcelableExtra("_VA_|_component_");
        int userId = intent.getIntExtra("_VA_|_user_id_", VUserHandle.USER_NULL);
        if (realIntent == null) {
            return false;
        }
        if (userId < 0) {
            VLog.w(TAG, "Sent a broadcast without userId " + realIntent);
            return false;
        }
        int vuid = VUserHandle.getUid(userId, appId);
        return handleUserBroadcast(vuid, info, component, realIntent, result);
    }

    private boolean handleUserBroadcast(int vuid, ActivityInfo info, ComponentName component, Intent realIntent, PendingResultData result) {
        if (component != null && !ComponentUtils.toComponentName(info).equals(component)) {
            // Verify the component.
            return false;
        }
        String originAction = SpecialComponentList.unprotectAction(realIntent.getAction());
        if (originAction != null) {
            // restore to origin action.
            realIntent.setAction(originAction);
        }
        return handleStaticBroadcastAsUser(vuid, info, realIntent, result);
    }

    private boolean handleStaticBroadcastAsUser(int vuid, ActivityInfo info, Intent intent,
                                                PendingResultData result) {
        ProcessRecord r;
        synchronized (mProcessNames) {
            r = findProcessLocked(info.processName, vuid);
        }
        if ((BROADCAST_NOT_STARTED_PKG
                || isStartProcessForBroadcast(info.processName, info.packageName)
                || GmsBroadcastProcessPolicy.shouldStart(
                        info.packageName, info.name, intent.getAction())
                || LinePushBroadcastPolicy.shouldStart(
                        info.packageName, info.processName, intent.getAction())) && r == null) {
            r = startProcessIfNeedLocked(info.processName, getUserId(vuid), info.packageName);
        }
        if (r == null || r.appThread == null) {
            return false;
        }
        final ProcessRecord target = r;
        Runnable dispatch = () -> performScheduleReceiver(
                target.client, vuid, info, intent, result);
        if (!mLinePushProcessGuard.protectAndDispatch(
                target, intent.getAction(), result, dispatch)) {
            dispatch.run();
        }
        return true;
    }

    private static boolean isStartProcessForBroadcast(String processName, String packageName) {
        return Constants.PRIVILEGE_APP.contains(packageName);
    }

    private void performScheduleReceiver(IVClient client, int vuid, ActivityInfo info, Intent intent,
                                         PendingResultData result) {

        ComponentName componentName = ComponentUtils.toComponentName(info);
        BroadcastSystem.get().broadcastSent(vuid, info, result);
        try {
            client.scheduleReceiver(info.processName, componentName, intent, result);
        } catch (Throwable e) {
            if (result != null) {
                BroadcastSystem.get().broadcastFinish(result);
            }
        }
    }

    void onStaticBroadcastFinished(IBinder token) {
        mLinePushProcessGuard.complete(token);
    }

    @Override
    public void broadcastFinish(PendingResultData res) {
        BroadcastSystem.get().broadcastFinish(res);
    }

    @Override
    public void notifyBadgerChange(BadgerInfo info) throws RemoteException {
        if (info == null) throw new SecurityException("Badger identity is required");
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(info.packageName, info.userId);
        Intent intent = new Intent(VASettings.ACTION_BADGER_CHANGE);
        intent.putExtra("userId", info.userId);
        intent.putExtra("packageName", info.packageName);
        intent.putExtra("badgerCount", info.badgerCount);
        VirtualCore.get().getContext().sendBroadcast(intent);
    }
}
