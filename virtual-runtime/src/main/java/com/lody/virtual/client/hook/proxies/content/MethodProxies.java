package com.lody.virtual.client.hook.proxies.content;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.hook.base.MethodProxy;

import java.lang.reflect.Method;

/**
 * author: weishu on 18/3/13.
 */
class MethodProxies {

    static int findTargetSdkArgumentIndex(Object[] args, int targetSdkVersion) {
        if (args == null) {
            return -1;
        }
        for (int i = 0; i < args.length; i++) {
            Object argument = args[i];
            if (argument instanceof Integer && (int) argument == targetSdkVersion) {
                return i;
            }
        }
        return -1;
    }

    private static boolean downgradeTargetSdkArgument(Object[] args) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        ApplicationInfo currentApplicationInfo = VClientImpl.get().getCurrentApplicationInfo();
        if (currentApplicationInfo == null) {
            return false;
        }
        int index = findTargetSdkArgumentIndex(
                args, currentApplicationInfo.targetSdkVersion);
        if (index == -1) {
            return false;
        }
        args[index] = Build.VERSION_CODES.N_MR1;
        return true;
    }

    static class RegisterContentObserver extends MethodProxy {

        @Override
        public String getMethodName() {
            return "registerContentObserver";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            downgradeTargetSdkArgument(args);
            return super.beforeCall(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class NotifyChange extends MethodProxy {

        @Override
        public String getMethodName() {
            return "notifyChange";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return super.beforeCall(who, method, args);
            }
            ApplicationInfo currentApplicationInfo = VClientImpl.get().getCurrentApplicationInfo();
            if (currentApplicationInfo == null) {
                return super.beforeCall(who, method, args);
            }
            int targetSdkVersion = currentApplicationInfo.targetSdkVersion;

            int index = MethodProxies.findTargetSdkArgumentIndex(args, targetSdkVersion);
            /*
            In ContentService, it contains this code:

            if (targetSdkVersion >= Build.VERSION_CODES.O) {
                throw new SecurityException(msg);
            } else {
                if (msg.startsWith("Failed to find provider")) {
                    // Sigh, we need to quietly let apps targeting older API
                    // levels notify on non-existent providers.
                } else {
                    Log.w(TAG, "Ignoring notify for " + uri + " from " + uid + ": " + msg);
                    return;
                }
            }
            we just modify the targetSdkVersion dynamic to fake it.
            */
            if (index != -1) {
                args[index] = Build.VERSION_CODES.N_MR1;
            }

            return super.beforeCall(who, method, args);
        }

        static int findTargetSdkArgumentIndex(Object[] args, int targetSdkVersion) {
            return MethodProxies.findTargetSdkArgumentIndex(args, targetSdkVersion);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }
}
