package com.lody.virtual.client.hook.proxies.notification;

import android.app.NotificationChannel;
import android.os.Build;
import android.os.IInterface;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.Inject;
import com.lody.virtual.client.hook.base.MethodInvocationProxy;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;
import com.lody.virtual.helper.compat.ParceledListSliceCompat;
import com.lody.virtual.helper.utils.DeviceUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mirror.android.app.NotificationManager;
import mirror.android.widget.Toast;

/**
 * @author Lody
 * @see android.app.NotificationManager
 * @see android.widget.Toast
 */
@Inject(MethodProxies.class)
public class NotificationManagerStub extends MethodInvocationProxy<MethodInvocationStub<IInterface>> {

    public NotificationManagerStub() {
        super(new MethodInvocationStub<IInterface>(NotificationManager.getService.call()));
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplaceCallingPkgMethodProxy("enqueueToast"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("cancelToast"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            addMethodProxy(new ReplaceCallingPkgMethodProxy("removeAutomaticZenRules"));
            addMethodProxy(new ReplaceCallingPkgMethodProxy("getImportance"));
            addMethodProxy(new ReplaceCallingPkgMethodProxy("areNotificationsEnabled"));
            addMethodProxy(new ReplaceCallingPkgMethodProxy("setNotificationPolicy"));
            addMethodProxy(new ReplaceCallingPkgMethodProxy("getNotificationPolicy"));
            addMethodProxy(new ReplaceCallingPkgMethodProxy("isNotificationPolicyAccessGrantedForPackage"));
        }

        // http://androidxref.com/8.0.0_r4/xref/frameworks/base/core/java/android/app/INotificationManager.aidl
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addMethodProxy(new ReplaceCallingPkgMethodProxy("createNotificationChannelGroups"));
            addMethodProxy(new StaticMethodProxy("getNotificationChannelGroups") {
                @Override
                public Object call(Object who, Method method, Object... args) throws Throwable {
                    if (containsGuestPackage(args)) {
                        return Collections.emptyList();
                    }
                    return super.call(who, method, args);
                }
            });
            addMethodProxy(new StaticMethodProxy("getNotificationChannelGroup") {
                @Override
                public Object call(Object who, Method method, Object... args) throws Throwable {
                    if (containsGuestPackage(args)) {
                        return null;
                    }
                    return super.call(who, method, args);
                }
            });
            addMethodProxy(new ReplaceCallingPkgMethodProxy("deleteNotificationChannelGroup"));
            addMethodProxy(new ReplaceCallingPkgMethodProxy("createNotificationChannels"));
            addMethodProxy(new StaticMethodProxy("getNotificationChannels") {
                @Override
                public Object call(Object who, Method method, Object... args) throws Throwable {
                    if (containsGuestPackage(args)) {
                        return callGetOwnNotificationChannels(
                                who, method, args, getAppPkg(), getHostPkg());
                    }
                    return super.call(who, method, args);
                }
            });
            addMethodProxy(new StaticMethodProxy("getNotificationChannel") {
                @Override
                public Object call(Object who, Method method, Object... args) throws Throwable {
                    return callGetOwnNotificationChannel(
                            who, method, args, getAppPkg(), getHostPkg());
                }
            });
            addMethodProxy(new ReplaceCallingPkgMethodProxy("deleteNotificationChannel"));
        }
        if (DeviceUtil.isSamsung()) {
            addMethodProxy(new ReplaceCallingPkgMethodProxy("removeEdgeNotification"));
        }
    }

    static boolean containsGuestPackage(Object[] args) {
        String hostPkg = VirtualCore.get().getHostPkg();
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                String value = (String) arg;
                if (VirtualCore.get().isAppInstalled(value) && !hostPkg.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    static Object callGetOwnNotificationChannel(Object who, Method method, Object[] args,
                                                String guestPackage, String hostPackage)
            throws Throwable {
        if (!rewriteOwnGuestPackage(args, guestPackage, hostPackage)) {
            return null;
        }
        return method.invoke(who, args);
    }

    static Object callGetOwnNotificationChannels(Object who, Method method, Object[] args,
                                                  String guestPackage, String hostPackage)
            throws Throwable {
        if (!rewriteOwnGuestChannelListPackages(args, guestPackage, hostPackage)) {
            return Collections.emptyList();
        }
        Object result = method.invoke(who, args);
        List channels = result instanceof List
                ? (List) result
                : ParceledListSliceCompat.getList(result);
        return filterOwnNotificationChannels(channels, guestPackage);
    }

    static boolean rewriteOwnGuestChannelListPackages(Object[] args, String guestPackage,
                                                       String hostPackage) {
        if (args == null || guestPackage == null || guestPackage.length() == 0
                || hostPackage == null || hostPackage.length() == 0
                || guestPackage.equals(hostPackage)) {
            return false;
        }

        // Earlier Android releases: getNotificationChannels(String packageName).
        if (args.length == 1 && guestPackage.equals(args[0])) {
            args[0] = hostPackage;
            return true;
        }

        // Recent Android releases: getNotificationChannels(
        //     String callingPackage, String targetPackage, int userId).
        if (args.length == 3
                && (guestPackage.equals(args[0]) || hostPackage.equals(args[0]))
                && guestPackage.equals(args[1])
                && args[2] instanceof Integer) {
            args[0] = hostPackage;
            args[1] = hostPackage;
            return true;
        }
        return false;
    }

    static List<NotificationChannel> filterOwnNotificationChannels(
            List channels, String guestPackage) {
        if (channels == null || guestPackage == null || guestPackage.length() == 0) {
            return Collections.emptyList();
        }
        ArrayList<NotificationChannel> ownChannels = new ArrayList<>();
        for (Object item : channels) {
            if (item instanceof NotificationChannel) {
                NotificationChannel channel = (NotificationChannel) item;
                if (isOwnNotificationChannelId(channel.getId(), guestPackage)) {
                    ownChannels.add(channel);
                }
            }
        }
        return ownChannels;
    }

    static boolean isOwnNotificationChannelId(String channelId, String guestPackage) {
        return channelId != null
                && guestPackage != null
                && (channelId.equals(guestPackage) || channelId.startsWith(guestPackage + "."));
    }

    static boolean rewriteOwnGuestPackage(Object[] args, String guestPackage, String hostPackage) {
        if (args == null || guestPackage == null || guestPackage.length() == 0
                || hostPackage == null || hostPackage.length() == 0
                || guestPackage.equals(hostPackage)) {
            return false;
        }

        // Android 8-9: getNotificationChannel(String packageName, String channelId).
        if (args.length == 2 && guestPackage.equals(args[0]) && args[1] instanceof String) {
            args[0] = hostPackage;
            return true;
        }

        // Current Android: getNotificationChannel(
        //     String callingPackage, int userId, String targetPackage, String channelId).
        // The target must be the current guest. The operation package can already be the host on
        // platform variants that normalize attribution before this hook runs, but no third-party
        // package is accepted in either identity slot.
        if (args.length == 4
                && (guestPackage.equals(args[0]) || hostPackage.equals(args[0]))
                && args[1] instanceof Integer
                && guestPackage.equals(args[2])
                && args[3] instanceof String) {
            args[0] = hostPackage;
            args[2] = hostPackage;
            return true;
        }
        return false;
    }

    @Override
    public void inject() throws Throwable {
        NotificationManager.sService.set(getInvocationStub().getProxyInterface());
        Toast.sService.set(getInvocationStub().getProxyInterface());
    }

    @Override
    public boolean isEnvBad() {
        return NotificationManager.getService.call() != getInvocationStub().getProxyInterface();
    }
}
