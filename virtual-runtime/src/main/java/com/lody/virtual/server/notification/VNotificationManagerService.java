package com.lody.virtual.server.notification;

import android.app.NotificationManager;
import android.content.Context;
import android.text.TextUtils;

import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.INotificationManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class VNotificationManagerService extends INotificationManager.Stub {
    private static final AtomicReference<VNotificationManagerService> gService = new AtomicReference<>();
    private NotificationManager mNotificationManager;
    static final String TAG = NotificationCompat.class.getSimpleName();
    private final List<String> mDisables = new ArrayList<>();
    //VApp's Notifications
    private final HashMap<String, List<NotificationInfo>> mNotifications = new HashMap<>();
    private Context mContext;
    private final NotificationStateStore mStateStore;

    private VNotificationManagerService(Context context) {
        mContext = context;
        mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mStateStore = new NotificationStateStore(VEnvironment.getNotificationConfigFile());
        try {
            NotificationStateStore.State state = mStateStore.read();
            mNotifications.putAll(state.notifications);
            mDisables.addAll(state.disabled);
        } catch (IOException damagedState) {
            // Ownership can no longer be proven. Cancel host-package notifications rather than
            // risk exposing a deleted user's notification after its numeric id is reused.
            mNotificationManager.cancelAll();
            mStateStore.delete();
            VLog.e(TAG, "Virtual notification state was rejected and cleared");
        }
    }

    public static void systemReady(Context context) {
        VNotificationManagerService instance = new VNotificationManagerService(context);
        gService.set(instance);
    }

    public static VNotificationManagerService get() {
        return gService.get();
    }

    public static VNotificationManagerService getOrCreate(Context context) {
        VNotificationManagerService service = gService.get();
        if (service != null) {
            return service;
        }
        systemReady(context);
        return gService.get();
    }

    /***
     * fake notification's id
     *
     * @param id          notification's id
     * @param packageName notification's package
     * @param userId      user
     * @return
     */
    @Override
    public int dealNotificationId(int id, String packageName, String tag, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(packageName, userId);
        return id;
    }

    /***
     * fake notification's tag
     *
     * @param id          notification's id
     * @param packageName notification's package
     * @param tag         notification's tag
     * @param userId      user
     * @return
     */
    @Override
    public String dealNotificationTag(int id, String packageName, String tag, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(packageName, userId);
        if (TextUtils.equals(mContext.getPackageName(), packageName)) {
            return tag;
        }
        if (tag == null) {
            return NotificationIdentity.namespaceUntaggedTag(packageName, userId);
        }
        return packageName + ":" + tag + "@" + userId;
    }

    @Override
    public boolean areNotificationsEnabledForPackage(String packageName, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(packageName, userId);
        synchronized (mDisables) {
            return !mDisables.contains(packageName + ":" + userId);
        }
    }

    @Override
    public void setNotificationsEnabledForPackage(String packageName, boolean enable, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(packageName, userId);
        String key = packageName + ":" + userId;
        synchronized (mDisables) {
            if (enable) {
                mDisables.remove(key);
            } else {
                if (!mDisables.contains(key)) {
                    mDisables.add(key);
                }
            }
        }
        saveStateBestEffort();
    }

    @Override
    public void addNotification(int id, String tag, String packageName, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(packageName, userId);
        NotificationInfo notificationInfo = new NotificationInfo(id, tag, packageName, userId);
        synchronized (mNotifications) {
            List<NotificationInfo> list = mNotifications.get(packageName);
            if (list == null) {
                list = new ArrayList<>();
                mNotifications.put(packageName, list);
            }
            if (!list.contains(notificationInfo)) {
                list.add(notificationInfo);
                saveStateBestEffort();
            }
        }
    }

    @Override
    public void cancelAllNotification(String packageName, int userId) {
        com.lody.virtual.server.VirtualUserAccessPolicy
                .enforceCallerPackageOrHost(packageName, userId);
        List<NotificationInfo> infos = new ArrayList<>();
        synchronized (mNotifications) {
            List<NotificationInfo> list = mNotifications.get(packageName);
            if (list != null) {
                int count = list.size();
                for (int i = count - 1; i >= 0; i--) {
                    NotificationInfo info = list.get(i);
                    if (info.userId == userId) {
                        infos.add(info);
                        list.remove(i);
                    }
                }
            }
        }
        for (NotificationInfo info : infos) {
            VLog.d(TAG, "cancel " + info.tag + " " + info.id);
            mNotificationManager.cancel(info.tag, info.id);
        }
        saveStateBestEffort();
    }

    /** Cancels and durably removes active notification ownership for one suspended package. */
    public void clearPackageState(String packageName, int userId) throws IOException {
        List<NotificationInfo> infos = new ArrayList<>();
        synchronized (mNotifications) {
            List<NotificationInfo> list = mNotifications.get(packageName);
            if (list != null) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    NotificationInfo info = list.get(i);
                    if (info.userId == userId) {
                        infos.add(info);
                        list.remove(i);
                    }
                }
                if (list.isEmpty()) {
                    mNotifications.remove(packageName);
                }
            }
        }
        for (NotificationInfo info : infos) {
            mNotificationManager.cancel(info.tag, info.id);
        }
        synchronized (mNotifications) {
            synchronized (mDisables) {
                mStateStore.write(mNotifications, mDisables);
            }
        }
    }

    public boolean hasPackageState(String packageName, int userId) {
        synchronized (mNotifications) {
            List<NotificationInfo> list = mNotifications.get(packageName);
            if (list == null) return false;
            for (NotificationInfo info : list) {
                if (info.userId == userId) return true;
            }
            return false;
        }
    }

    /** Cancels all host notifications and removes notification policy for one virtual user. */
    public void clearUserState(int userId) throws IOException {
        List<NotificationInfo> infos = new ArrayList<>();
        synchronized (mNotifications) {
            for (List<NotificationInfo> list : mNotifications.values()) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    NotificationInfo info = list.get(i);
                    if (info.userId == userId) {
                        infos.add(info);
                        list.remove(i);
                    }
                }
            }
        }
        String suffix = ":" + userId;
        synchronized (mDisables) {
            for (int i = mDisables.size() - 1; i >= 0; i--) {
                if (mDisables.get(i).endsWith(suffix)) {
                    mDisables.remove(i);
                }
            }
        }
        for (NotificationInfo info : infos) {
            mNotificationManager.cancel(info.tag, info.id);
        }
        synchronized (mNotifications) {
            synchronized (mDisables) {
                mStateStore.write(mNotifications, mDisables);
            }
        }
    }

    private void saveStateBestEffort() {
        try {
            synchronized (mNotifications) {
                synchronized (mDisables) {
                    mStateStore.write(mNotifications, mDisables);
                }
            }
        } catch (IOException failure) {
            VLog.e(TAG, "Unable to persist virtual notification ownership");
        }
    }

    static class NotificationInfo {
        int id;
        String tag;
        String packageName;
        int userId;

        NotificationInfo(int id, String tag, String packageName, int userId) {
            this.id = id;
            this.tag = tag;
            this.packageName = packageName;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof NotificationInfo) {
                NotificationInfo that = (NotificationInfo) obj;
                return that.id == id && TextUtils.equals(that.tag, tag)
                        && TextUtils.equals(packageName, that.packageName)
                        && that.userId == userId;
            }
            return super.equals(obj);
        }
    }

}
