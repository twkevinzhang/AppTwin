package com.lody.virtual.client.hook.proxies.notification;

import android.app.Notification;
import android.os.Build;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.client.ipc.VNotificationManager;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;

import java.lang.reflect.Method;
import java.util.Collections;

/**
 * @author Lody
 */

@SuppressWarnings("unused")
class MethodProxies {

    static class GetAppActiveNotifications extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getAppActiveNotifications";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return callGetAppActiveNotifications(who, method, args, getHostPkg());
        }
    }

    static Object callGetAppActiveNotifications(Object who, Method method, Object[] args,
                                                String hostPkg) throws Throwable {
        if (isHostActiveNotificationsRequest(args, hostPkg)) {
            return method.invoke(who, args);
        }
        return Collections.emptyList();
    }

    static boolean isHostActiveNotificationsRequest(Object[] args, String hostPkg) {
        return hostPkg != null
                && args != null
                && args.length > 0
                && args[0] instanceof String
                && hostPkg.equals(args[0]);
    }

    static class EnqueueNotification extends MethodProxy {

        @Override
        public String getMethodName() {
            return "enqueueNotification";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            if (getHostPkg().equals(pkg)) {
                return method.invoke(who, args);
            }
            int notificationIndex = ArrayUtils.indexOfFirst(args, Notification.class);
            int idIndex = ArrayUtils.indexOfFirst(args, Integer.class);
            int id = (int) args[idIndex];
            id = VNotificationManager.get().dealNotificationId(id, pkg, null, getAppUserId());
            args[idIndex] = id;
            Notification notification = (Notification) args[notificationIndex];
            applySpaceLabel(notification, getAppUserId());
            if (!VNotificationManager.get().dealNotification(id, notification, pkg)) {
                return 0;
            }
            VNotificationManager.get().addNotification(id, null, pkg, getAppUserId());
            args[0] = getHostPkg();
            return method.invoke(who, args);
        }
    }

    /* package */ static class EnqueueNotificationWithTag extends MethodProxy {

        @Override
        public String getMethodName() {
            return "enqueueNotificationWithTag";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            if (getHostPkg().equals(pkg)) {
                return method.invoke(who, args);
            }
            int notificationIndex = ArrayUtils.indexOfFirst(args, Notification.class);
            int idIndex = ArrayUtils.indexOfFirst(args, Integer.class);
            int tagIndex = (Build.VERSION.SDK_INT >= 18 ? 2 : 1);
            int id = (int) args[idIndex];
            String tag = (String) args[tagIndex];

            id = VNotificationManager.get().dealNotificationId(id, pkg, tag, getAppUserId());
            tag = VNotificationManager.get().dealNotificationTag(id, pkg, tag, getAppUserId());
            args[idIndex] = id;
            args[tagIndex] = tag;
            //key(tag,id)
            Notification notification = (Notification) args[notificationIndex];
            applySpaceLabel(notification, getAppUserId());
            if (!VNotificationManager.get().dealNotification(id, notification, pkg)) {
                return 0;
            }
            VNotificationManager.get().addNotification(id, tag, pkg, getAppUserId());
            args[0] = getHostPkg();
            if (Build.VERSION.SDK_INT >= 18 && args[1] instanceof String) {
                args[1] = getHostPkg();
            }
            return method.invoke(who, args);
        }
    }

    private static void applySpaceLabel(Notification notification, int userId) {
        if (notification == null || notification.extras == null) {
            return;
        }
        VUserInfo user = VUserManager.get().getUserInfo(userId);
        String spaceName = user == null ? null : user.name;
        CharSequence existing = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        notification.extras.putCharSequence(
                Notification.EXTRA_SUB_TEXT,
                NotificationSpaceLabeler.label(spaceName, existing));
    }

    /* package */ static class EnqueueNotificationWithTagPriority extends EnqueueNotificationWithTag {

        @Override
        public String getMethodName() {
            return "enqueueNotificationWithTagPriority";
        }
    }

    /* package */ static class CancelNotificationWithTag extends MethodProxy {

        @Override
        public String getMethodName() {
            return "cancelNotificationWithTag";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = MethodParameterUtils.replaceFirstAppPkg(args);
            if (getHostPkg().equals(pkg)) {
                return method.invoke(who, args);
            }
            
            int index_tag = 1;
            int index_id = 2;

            if (Build.VERSION.SDK_INT >= 30) {
                index_tag = 2;
                index_id = 3;
            }
            
            String tag = (String) args[index_tag];
            int id = (int) args[index_id];

            id = VNotificationManager.get().dealNotificationId(id, pkg, tag, getAppUserId());
            tag = VNotificationManager.get().dealNotificationTag(id, pkg, tag, getAppUserId());

            args[index_tag] = tag;
            args[index_id] = id;
            rewriteCancelPackagesForSystem(args, getHostPkg(), Build.VERSION.SDK_INT >= 30);
            return method.invoke(who, args);
        }
    }

    static void rewriteCancelPackagesForSystem(
            Object[] args, String hostPackage, boolean hasOperationPackage) {
        if (args == null || args.length == 0) {
            return;
        }
        args[0] = hostPackage;
        if (hasOperationPackage && args.length > 1 && args[1] instanceof String) {
            args[1] = hostPackage;
        }
    }

    /**
     * @author Lody
     */
    /* package */ static class CancelAllNotifications extends MethodProxy {

        @Override
        public String getMethodName() {
            return "cancelAllNotifications";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = MethodParameterUtils.replaceFirstAppPkg(args);
            if (VirtualCore.get().isAppInstalled(pkg)) {
                VNotificationManager.get().cancelAllNotification(pkg, getAppUserId());
                return 0;
            }
            return method.invoke(who, args);
        }
    }

    static class AreNotificationsEnabledForPackage extends MethodProxy {
        @Override
        public String getMethodName() {
            return "areNotificationsEnabledForPackage";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            if (getHostPkg().equals(pkg)) {
                return method.invoke(who, args);
            }
            return VNotificationManager.get().areNotificationsEnabledForPackage(pkg, getAppUserId());
        }
    }

    static class SetNotificationsEnabledForPackage extends MethodProxy {
        @Override
        public String getMethodName() {
            return "setNotificationsEnabledForPackage";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            if (getHostPkg().equals(pkg)) {
                return method.invoke(who, args);
            }
            int enableIndex = ArrayUtils.indexOfFirst(args, Boolean.class);
            boolean enable = (boolean) args[enableIndex];
            VNotificationManager.get().setNotificationsEnabledForPackage(pkg, enable, getAppUserId());
            return 0;
        }
    }
}
