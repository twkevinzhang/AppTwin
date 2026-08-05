package com.lody.virtual.helper.compat;

import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import mirror.android.app.IApplicationThread;
import mirror.android.app.IApplicationThreadICSMR1;
import mirror.android.app.IApplicationThreadKitkat;
import mirror.android.app.IApplicationThreadOreo;
import mirror.android.app.ServiceStartArgs;
import mirror.android.content.res.CompatibilityInfo;

/**
 * @author Lody
 */

public class IApplicationThreadCompat {

    public static void scheduleCreateService(IInterface appThread, IBinder token, ServiceInfo info,
                                             int processState) throws RemoteException {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            IApplicationThreadKitkat.scheduleCreateService.call(appThread, token, info, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO.get(),
                    processState);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            IApplicationThreadICSMR1.scheduleCreateService.call(appThread, token, info, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO.get());
        } else {
            IApplicationThread.scheduleCreateService.call(appThread, token, info);
        }

    }

    public static void scheduleBindService(IInterface appThread, IBinder token, Intent intent, boolean rebind,
                                           int processState) throws RemoteException {
        if (usesBindSequence(Build.VERSION.SDK_INT)) {
            invokeAndroid17ScheduleBindService(
                    appThread, token, intent, rebind, processState, 0L);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            IApplicationThreadKitkat.scheduleBindService.call(appThread, token, intent, rebind, processState);
        } else {
            IApplicationThread.scheduleBindService.call(appThread, token, intent, rebind);
        }
    }

    public static boolean usesBindSequence(int sdkInt) {
        return sdkInt >= 37;
    }

    private static void invokeAndroid17ScheduleBindService(IInterface appThread, IBinder token,
                                                            Intent intent, boolean rebind,
                                                            int processState, long bindSeq)
            throws RemoteException {
        try {
            Method method = findScheduleBindServiceMethod(appThread.getClass());
            if (method == null) {
                throw new NoSuchMethodException("scheduleBindService(IBinder, Intent, boolean, int, long)");
            }
            method.setAccessible(true);
            method.invoke(appThread, token, intent, rebind, processState, bindSeq);
        } catch (InvocationTargetException e) {
            throwAsRemoteException(e.getCause() != null ? e.getCause() : e);
        } catch (Throwable e) {
            throwAsRemoteException(e);
        }
    }

    static Method findScheduleBindServiceMethod(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("scheduleBindService".equals(method.getName())
                        && parameters.length == 5
                        && IBinder.class.isAssignableFrom(parameters[0])
                        && Intent.class.isAssignableFrom(parameters[1])
                        && parameters[2] == boolean.class
                        && parameters[3] == int.class
                        && parameters[4] == long.class) {
                    return method;
                }
            }
        }
        return null;
    }

    private static void throwAsRemoteException(Throwable throwable) throws RemoteException {
        if (throwable instanceof RemoteException) {
            throw (RemoteException) throwable;
        }
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        RemoteException remoteException = new RemoteException(
                "Unable to schedule Android 17 service binding: " + throwable);
        remoteException.initCause(throwable);
        throw remoteException;
    }

    public static void scheduleUnbindService(IInterface appThread, IBinder token, Intent intent) throws RemoteException {
        IApplicationThread.scheduleUnbindService.call(appThread, token, intent);
    }

    public static void scheduleServiceArgs(IInterface appThread, IBinder token, boolean taskRemoved,
                                           int startId, int flags, Intent args) throws RemoteException {

        if (Build.VERSION.SDK_INT >= 26) {
            List<Object> list = new ArrayList<>(1);
            Object serviceStartArg = ServiceStartArgs.ctor.newInstance(taskRemoved, startId, flags, args);
            list.add(serviceStartArg);
            IApplicationThreadOreo.scheduleServiceArgs.call(appThread, token, ParceledListSliceCompat.create(list));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            IApplicationThreadICSMR1.scheduleServiceArgs.call(appThread, token, taskRemoved, startId, flags, args);
        } else {
            IApplicationThread.scheduleServiceArgs.call(appThread, token, startId, flags, args);
        }
    }


    public static void scheduleStopService(IInterface appThread, IBinder token) throws RemoteException {
        IApplicationThread.scheduleStopService.call(appThread, token);
    }

}
