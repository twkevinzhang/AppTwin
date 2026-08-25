package com.lody.virtual.server.am;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.SpecialComponentList;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.utils.BroadcastPackageScope;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.PendingResultData;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.VAppManagerService;
import com.lody.virtual.server.pm.parser.VPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import mirror.android.app.ContextImpl;
import mirror.android.app.LoadedApkHuaWei;
import mirror.android.rms.resource.ReceiverResourceLP;
import mirror.android.rms.resource.ReceiverResourceM;
import mirror.android.rms.resource.ReceiverResourceN;

import static android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY;

/**
 * @author Lody
 */

public class BroadcastSystem {

    private static final String TAG = BroadcastSystem.class.getSimpleName();
    /**
     * MUST < 10000.
     */
    private static final int BROADCAST_TIME_OUT = 8500;
    private static final String INTERNAL_BROADCAST_PERMISSION_SUFFIX =
            ".permission.INTERNAL_BROADCAST";
    private static BroadcastSystem gDefault;

    private final ArrayMap<String, List<BroadcastReceiver>> mReceivers = new ArrayMap<>();
    private final Map<IBinder, BroadcastRecord> mBroadcastRecords = new HashMap<>();
    private final Context mContext;
    private final StaticScheduler mScheduler;
    private final TimeoutHandler mTimeoutHandler;
    private final VActivityManagerService mAMS;
    private final VAppManagerService mApp;

    private BroadcastSystem(Context context, VActivityManagerService ams, VAppManagerService app) {
        this.mContext = context;
        this.mApp = app;
        this.mAMS = ams;
        mScheduler = new StaticScheduler();
        mTimeoutHandler = new TimeoutHandler();
        fuckHuaWeiVerifier();
    }

    public static void attach(VActivityManagerService ams, VAppManagerService app) {
        if (gDefault != null) {
            throw new IllegalStateException();
        }
        gDefault = new BroadcastSystem(VirtualCore.get().getContext(), ams, app);
    }

    public static BroadcastSystem get() {
        return gDefault;
    }

    /**
     * FIX ISSUE #171:
     * java.lang.AssertionError: Register too many Broadcast Receivers
     * at android.app.LoadedApk.checkRecevierRegisteredLeakLocked(LoadedApk.java:772)
     * at android.app.LoadedApk.getReceiverDispatcher(LoadedApk.java:800)
     * at android.app.ContextImpl.registerReceiverInternal(ContextImpl.java:1329)
     * at android.app.ContextImpl.registerReceiver(ContextImpl.java:1309)
     * at com.lody.virtual.server.am.BroadcastSystem.startApp(BroadcastSystem.java:54)
     * at com.lody.virtual.server.pm.VAppManagerService.install(VAppManagerService.java:193)
     * at com.lody.virtual.server.pm.VAppManagerService.preloadAllApps(VAppManagerService.java:98)
     * at com.lody.virtual.server.pm.VAppManagerService.systemReady(VAppManagerService.java:70)
     * at com.lody.virtual.server.BinderProvider.onCreate(BinderProvider.java:42)
     */
    private void fuckHuaWeiVerifier() {

        if (LoadedApkHuaWei.mReceiverResource != null) {
            Object packageInfo = ContextImpl.mPackageInfo.get(mContext);
            if (packageInfo != null) {
                Object receiverResource = LoadedApkHuaWei.mReceiverResource.get(packageInfo);
                if (receiverResource != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        if (ReceiverResourceN.mWhiteList != null) {
                            List<String> whiteList = ReceiverResourceN.mWhiteList.get(receiverResource);
                            List<String> newWhiteList = new ArrayList<>();
                            // Add our package name to the white list.
                            newWhiteList.add(mContext.getPackageName());
                            if (whiteList != null) {
                                newWhiteList.addAll(whiteList);
                            }
                            ReceiverResourceN.mWhiteList.set(receiverResource, newWhiteList);
                        }

                    } else {
                        if (ReceiverResourceM.mWhiteList != null) {
                            String[] whiteList = ReceiverResourceM.mWhiteList.get(receiverResource);
                            List<String> newWhiteList = new LinkedList<>();
                            Collections.addAll(newWhiteList, whiteList);
                            // Add our package name to the white list.
                            newWhiteList.add(mContext.getPackageName());
                            ReceiverResourceM.mWhiteList.set(receiverResource, newWhiteList.toArray(new String[newWhiteList.size()]));
                        } else if (ReceiverResourceLP.mResourceConfig != null) {
                            // Just clear the ResourceConfig.
                            ReceiverResourceLP.mResourceConfig.set(receiverResource, null);
                        }
                    }
                }
            }
        }
    }

    public void startApp(VPackage p) {
        PackageSetting setting = (PackageSetting) p.mExtras;
        for (VPackage.ActivityComponent receiver : p.receivers) {
            ActivityInfo info = receiver.info;
            List<BroadcastReceiver> receivers = mReceivers.get(p.packageName);
            if (receivers == null) {
                receivers = new ArrayList<>();
                mReceivers.put(p.packageName, receivers);
            }
            String componentAction = String.format("_VA_%s_%s", info.packageName, info.name);
            IntentFilter componentFilter = new IntentFilter(componentAction);
            BroadcastReceiver r = new StaticBroadcastReceiver(
                    setting.appId, info, componentFilter, true);
            registerReceiver(r, componentFilter, false);
            receivers.add(r);
            for (VPackage.ActivityIntentInfo ci : receiver.intents) {
                IntentFilter cloneFilter = new IntentFilter(ci.filter);
                SpecialComponentList.protectIntentFilter(cloneFilter);
                IntentFilter internalFilter = new IntentFilter(cloneFilter);
                SpecialComponentList.retainSystemBroadcastActions(internalFilter, false);
                if (internalFilter.countActions() > 0) {
                    r = new StaticBroadcastReceiver(setting.appId, info, internalFilter, true);
                    registerReceiver(r, internalFilter, false);
                    receivers.add(r);
                }
                IntentFilter systemFilter = new IntentFilter(cloneFilter);
                SpecialComponentList.retainSystemBroadcastActions(systemFilter, true);
                if (systemFilter.countActions() > 0) {
                    r = new StaticBroadcastReceiver(setting.appId, info, systemFilter, false);
                    registerReceiver(r, systemFilter, true);
                    receivers.add(r);
                }
            }
        }
    }

    private void registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                  boolean exported) {
        String requiredPermission = requiredPermission(mContext.getPackageName(), exported);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Protected wrappers are emitted by another AppTwin process. API 33+ has varied in
            // how aggressively RECEIVER_NOT_EXPORTED constrains cross-process delivery, so make
            // the receiver addressable but require an AppTwin signature permission. Untrusted
            // applications cannot spoof these wrappers.
            mContext.registerReceiver(receiver, filter, requiredPermission, mScheduler,
                    receiverFlags());
        } else {
            mContext.registerReceiver(receiver, filter, requiredPermission, mScheduler);
        }
    }

    static String internalBroadcastPermission(Context context) {
        return internalBroadcastPermission(context.getPackageName());
    }

    static String internalBroadcastPermission(String packageName) {
        return packageName + INTERNAL_BROADCAST_PERMISSION_SUFFIX;
    }

    static String requiredPermission(String packageName, boolean systemReceiver) {
        return systemReceiver ? null : internalBroadcastPermission(packageName);
    }

    static int receiverFlags() {
        return Context.RECEIVER_EXPORTED;
    }


    public void stopApp(String packageName) {
        LinePushStopFence.StopScope lineStop =
                mAMS.beginStaticBroadcastAppStop(packageName);
        try {
            synchronized (mBroadcastRecords) {
                Iterator<Map.Entry<IBinder, BroadcastRecord>> iterator = mBroadcastRecords.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<IBinder, BroadcastRecord> entry = iterator.next();
                    BroadcastRecord record = entry.getValue();
                    if (record.receiverInfo.packageName.equals(packageName)) {
                        record.pendingResult.finish();
                        mAMS.onStaticBroadcastFinished(entry.getKey());
                        LinePushDeliveryDiagnostics.finish(entry.getKey(), "app-stopped");
                        iterator.remove();
                    }
                }
            }
            synchronized (mReceivers) {
                List<BroadcastReceiver> receivers = mReceivers.get(packageName);
                if (receivers != null) {
                    for (BroadcastReceiver r : receivers) {
                        mContext.unregisterReceiver(r);
                    }
                }
                mReceivers.remove(packageName);
            }
        } finally {
            mAMS.endStaticBroadcastAppStop(lineStop);
        }
    }

    void broadcastFinish(PendingResultData res) {
        synchronized (mBroadcastRecords) {
            BroadcastRecord record = mBroadcastRecords.remove(res.mToken);
            if (record == null) {
                VLog.e(TAG, "Unable to find the BroadcastRecord for completed receiver");
            }
        }
        mTimeoutHandler.removeMessages(0, res.mToken);
        mAMS.onStaticBroadcastFinished(res.mToken);
        LinePushDeliveryDiagnostics.finish(res.mToken, "completed");
        res.finish();
    }

    void broadcastSent(int vuid, ActivityInfo receiverInfo, PendingResultData res) {
        BroadcastRecord record = new BroadcastRecord(vuid, receiverInfo, res);
        synchronized (mBroadcastRecords) {
            mBroadcastRecords.put(res.mToken, record);
        }
        Message msg = new Message();
        msg.obj = res.mToken;
        mTimeoutHandler.sendMessageDelayed(msg, BROADCAST_TIME_OUT);
    }

    private static final class StaticScheduler extends Handler {

    }

    private static final class BroadcastRecord {
        int vuid;
        ActivityInfo receiverInfo;
        PendingResultData pendingResult;

        BroadcastRecord(int vuid, ActivityInfo receiverInfo, PendingResultData pendingResult) {
            this.vuid = vuid;
            this.receiverInfo = receiverInfo;
            this.pendingResult = pendingResult;
        }
    }

    private final class TimeoutHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            IBinder token = (IBinder) msg.obj;
            BroadcastRecord r;
            synchronized (mBroadcastRecords) {
                r = mBroadcastRecords.remove(token);
            }
            if (r != null) {
                VLog.w(TAG, "Broadcast timeout, cancel to dispatch it.");
                mAMS.onStaticBroadcastFinished(token);
                LinePushDeliveryDiagnostics.finish(token, "timeout");
                r.pendingResult.finish();
            }
        }
    }


    private final class StaticBroadcastReceiver extends BroadcastReceiver {
        private int appId;
        private ActivityInfo info;
        private final boolean signatureProtectedWrapper;
        @SuppressWarnings("unused")
        private IntentFilter filter;

        private StaticBroadcastReceiver(int appId, ActivityInfo info, IntentFilter filter,
                boolean signatureProtectedWrapper) {
            this.appId = appId;
            this.info = info;
            this.filter = filter;
            this.signatureProtectedWrapper = signatureProtectedWrapper;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mApp.isBooting()) {
                return;
            }
            if ((intent.getFlags() & FLAG_RECEIVER_REGISTERED_ONLY) != 0 || isInitialStickyBroadcast()) {
                return;
            }
            String targetPackage = intent.getStringExtra(
                    BroadcastPackageScope.EXTRA_TARGET_PACKAGE);
            String virtualSenderPackage = intent.getStringExtra(
                    BroadcastPackageScope.EXTRA_VIRTUAL_SENDER_PACKAGE);
            int virtualSenderVuid = intent.getIntExtra(
                    BroadcastPackageScope.EXTRA_VIRTUAL_SENDER_VUID, -1);
            int virtualSenderUserId = intent.getIntExtra(
                    BroadcastPackageScope.EXTRA_VIRTUAL_SENDER_USER_ID, VUserHandle.USER_NULL);
            String linePushAttestation = intent.getStringExtra(
                    BroadcastPackageScope.EXTRA_LINE_PUSH_ATTESTATION);
            boolean accepted = BroadcastPackageScope.accepts(targetPackage, info.packageName);
            boolean lineC2dm = LinePushDeliveryDiagnostics.isLineC2dmWrapper(
                    intent, info.packageName);
            if (lineC2dm) {
                LinePushDeliveryDiagnostics.wrapperScope(
                        intent.getIntExtra("_VA_|_user_id_", -1),
                        targetPackage, info.packageName, accepted);
            }
            if (!accepted) {
                return;
            }
            PendingResult result = goAsync();
            PendingResultData resultData = new PendingResultData(result);
            if (lineC2dm) {
                LinePushDeliveryDiagnostics.begin(resultData,
                        intent.getIntExtra("_VA_|_user_id_", -1),
                        targetPackage, info.packageName);
            }
            if (!mAMS.handleStaticBroadcast(appId, info, intent, resultData,
                    signatureProtectedWrapper, targetPackage,
                    virtualSenderPackage, virtualSenderVuid, virtualSenderUserId,
                    linePushAttestation)) {
                LinePushDeliveryDiagnostics.finish(resultData.mToken, "entry-rejected");
                result.finish();
            }
        }
    }
}
