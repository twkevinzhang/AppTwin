package com.lody.virtual.client.hook.proxies.am;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Build;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.GuestPackageIdentity;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.interfaces.IInjector;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.remote.StubActivityRecord;

import mirror.android.app.ActivityManagerNative;
import mirror.android.app.ActivityThread;
import mirror.android.app.IActivityManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

enum HCallbackLaunchHandling {
    DELEGATE,
    RETRY_QUEUED,
    ABORT_CONSUMED
}

final class HCallbackLaunchPolicy {
    private HCallbackLaunchPolicy() { }

    static boolean shouldConsume(HCallbackLaunchHandling handling) {
        return handling != HCallbackLaunchHandling.DELEGATE;
    }
}

/**
     * @author Lody
     * @see Handler.Callback
     */
    public class HCallbackStub implements Handler.Callback, IInjector {


        private static int LAUNCH_ACTIVITY = -1;
        private static final int CREATE_SERVICE = ActivityThread.H.CREATE_SERVICE.get();
        private static final int SCHEDULE_CRASH =
                ActivityThread.H.SCHEDULE_CRASH != null ? ActivityThread.H.SCHEDULE_CRASH.get() : -1;
        private static final int EXECUTE_TRANSACTION = resolveExecuteTransaction();
        private static final Set<IBinder> STALE_ACTIVITY_TOKENS =
                Collections.newSetFromMap(new ConcurrentHashMap<IBinder, Boolean>());

    private static final String TAG = HCallbackStub.class.getSimpleName();
    private static final long ACTIVITY_BOOTSTRAP_WAIT_MILLIS = 750L;
        private static final HCallbackStub sCallback = new HCallbackStub();

        private boolean mCalling = false;


        private Handler.Callback otherCallback;

        static {
            if (android.os.Build.VERSION.SDK_INT < 28) {
                LAUNCH_ACTIVITY = ActivityThread.H.LAUNCH_ACTIVITY.get();
            }
        }
        private HCallbackStub() {
        }

        public static HCallbackStub getDefault() {
            return sCallback;
        }

        private static Handler getH() {
            return ActivityThread.mH.get(VirtualCore.mainThread());
        }

        private static Handler.Callback getHCallback() {
            try {
                Handler handler = getH();
                return mirror.android.os.Handler.mCallback.get(handler);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        public boolean handleMessage(Message msg) {
            if (!mCalling) {
                mCalling = true;
                try {
                    if (LAUNCH_ACTIVITY == msg.what) {
                        if (HCallbackLaunchPolicy.shouldConsume(handleLaunchActivity(msg))) {
                            return true;
                        }
                    } else if (CREATE_SERVICE == msg.what) {
                        if (!VClientImpl.get().isBound()) {
                            ServiceInfo info = Reflect.on(msg.obj).get("info");
                            if (GuestServiceBindingPolicy.shouldBindGuestApplication(
                                    VirtualCore.get().getHostPkg(), info.packageName)) {
                                VClientImpl.get().bindApplication(info.packageName, info.processName);
                            }
                        }
                    } else if (SCHEDULE_CRASH == msg.what) {
                        // to avoid the exception send from System.
                        return true;
                    } else if (EXECUTE_TRANSACTION == msg.what
                            && shouldConsumeMissingGuestLaunch(msg.obj)) {
                        return true;
                    }
                    if (otherCallback != null) {
                        boolean desired = otherCallback.handleMessage(msg);
                        mCalling = false;
                        return desired;
                    } else {
                        mCalling = false;
                    }
                } finally {
                    mCalling = false;
                }
            }
            return false;
        }

        private static int resolveExecuteTransaction() {
            if (Build.VERSION.SDK_INT < 28) return -1;
            try {
                Class<?> handlerClass = Class.forName("android.app.ActivityThread$H");
                Field field = handlerClass.getDeclaredField("EXECUTE_TRANSACTION");
                field.setAccessible(true);
                return field.getInt(null);
            } catch (Throwable ignored) {
                return 159;
            }
        }

        private static boolean shouldConsumeMissingGuestLaunch(Object transaction) {
            IBinder activityToken = activityToken(transaction);
            if (activityToken != null && STALE_ACTIVITY_TOKENS.contains(activityToken)) {
                VLog.i(TAG, "ignore follow-up transaction for stale activity token");
                return true;
            }
            for (Object item : transactionCallbacks(transaction)) {
                Intent stubIntent = intentField(item);
                if (stubIntent == null) continue;
                StubActivityRecord record = new StubActivityRecord(stubIntent);
                ActivityInfo info = record.info;
                if (record.intent == null || info == null) continue;
                if (VirtualCore.get().getInstalledAppInfo(info.packageName, 0) == null) {
                    if (activityToken != null) STALE_ACTIVITY_TOKENS.add(activityToken);
                    VLog.i(TAG, "ignore stale launch transaction for missing guest: "
                            + info.packageName);
                    return true;
                }
            }
            return false;
        }

        private static IBinder activityToken(Object transaction) {
            if (transaction == null) return null;
            try {
                Method method = transaction.getClass().getDeclaredMethod("getActivityToken");
                method.setAccessible(true);
                Object value = method.invoke(transaction);
                return value instanceof IBinder ? (IBinder) value : null;
            } catch (Throwable ignored) {
                try {
                    Field field = findField(transaction.getClass(), "mActivityToken");
                    if (field == null) return null;
                    field.setAccessible(true);
                    Object value = field.get(transaction);
                    return value instanceof IBinder ? (IBinder) value : null;
                } catch (Throwable ignoredAgain) {
                    return null;
                }
            }
        }

        private static List<?> transactionCallbacks(Object transaction) {
            if (transaction == null) return Collections.emptyList();
            try {
                Method method = transaction.getClass().getDeclaredMethod("getCallbacks");
                method.setAccessible(true);
                Object value = method.invoke(transaction);
                return value instanceof List ? (List<?>) value : Collections.emptyList();
            } catch (Throwable ignored) {
                try {
                    Field field = findField(transaction.getClass(), "mActivityCallbacks");
                    if (field == null) return Collections.emptyList();
                    field.setAccessible(true);
                    Object value = field.get(transaction);
                    return value instanceof List ? (List<?>) value : Collections.emptyList();
                } catch (Throwable ignoredAgain) {
                    return Collections.emptyList();
                }
            }
        }

        private static Intent intentField(Object item) {
            if (item == null) return null;
            try {
                Field field = findField(item.getClass(), "mIntent");
                if (field == null) return null;
                field.setAccessible(true);
                Object value = field.get(item);
                return value instanceof Intent ? (Intent) value : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Field findField(Class<?> type, String name) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // Continue through vendor/AOSP superclass variants.
                }
            }
            return null;
        }

        private HCallbackLaunchHandling handleLaunchActivity(Message msg) {
            Object r = msg.obj;
            Intent stubIntent = ActivityThread.ActivityClientRecord.intent.get(r);
            StubActivityRecord saveInstance = new StubActivityRecord(stubIntent);
            if (saveInstance.intent == null) {
                return HCallbackLaunchHandling.DELEGATE;
            }
            Intent intent = saveInstance.intent;
            ComponentName caller = saveInstance.caller;
            IBinder token = ActivityThread.ActivityClientRecord.token.get(r);
            ActivityInfo info = saveInstance.info;
            if (VClientImpl.get().getToken() == null) {
                InstalledAppInfo installedAppInfo = VirtualCore.get().getInstalledAppInfo(info.packageName, 0);
                if(installedAppInfo == null){
                    return HCallbackLaunchHandling.DELEGATE;
                }
                VLog.i(TAG, "activity bootstrap missing owner; requesting exact reservation check"
                        + " user=" + saveInstance.userId);
                if (!VClientImpl.get().awaitActivityProcessOwner(
                        info.packageName, info.processName, saveInstance.userId,
                        ACTIVITY_BOOTSTRAP_WAIT_MILLIS)) {
                    VLog.e(TAG, "activity bootstrap consumed reason=owner-timeout-or-rejected"
                            + " user=" + saveInstance.userId);
                    return HCallbackLaunchHandling.ABORT_CONSUMED;
                }
            }
            if (!VClientImpl.get().isBound()) {
                VClientImpl.get().bindApplicationForActivity(info.packageName, info.processName, intent);
                if (!VClientImpl.get().isBound()) {
                    VLog.e(TAG, "activity bootstrap consumed reason=application-not-bound"
                            + " user=" + saveInstance.userId);
                    return HCallbackLaunchHandling.ABORT_CONSUMED;
                }
            }
            int taskId = IActivityManager.getTaskForActivity.call(
                    ActivityManagerNative.getDefault.call(),
                    token,
                    false
            );
            if (VActivityManager.get().onActivityCreate(ComponentUtils.toComponentName(info), caller,
                    token, info, intent, ComponentUtils.getTaskAffinity(info), taskId,
                    info.launchMode, info.flags, saveInstance.preparedLaunchId) == null) {
                return HCallbackLaunchHandling.ABORT_CONSUMED;
            }
            GuestPackageIdentity.exposeHostUid(info.applicationInfo, VirtualCore.get().myUid());
            ClassLoader appClassLoader = VClientImpl.get().getClassLoader(info.applicationInfo);
            intent.setExtrasClassLoader(appClassLoader);
            ActivityThread.ActivityClientRecord.intent.set(r, intent);
            ActivityThread.ActivityClientRecord.activityInfo.set(r, info);
            return HCallbackLaunchHandling.DELEGATE;
        }

        @Override
        public void inject() throws Throwable {
            otherCallback = getHCallback();
            mirror.android.os.Handler.mCallback.set(getH(), this);
        }

        @Override
        public boolean isEnvBad() {
            Handler.Callback callback = getHCallback();
            boolean envBad = callback != this;
            if (callback != null && envBad) {
                VLog.d(TAG, "HCallback has bad, other callback = " + callback);
            }
            return envBad;
        }

    }
