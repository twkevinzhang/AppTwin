package com.lody.virtual.client.stub;

import android.app.Service;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;

import com.lody.virtual.client.isolated.IIsolatedGuestWorker;
import com.lody.virtual.client.isolated.IsolatedWorkerCallGate;
import com.lody.virtual.client.env.VirtualRuntime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import mirror.android.app.ActivityManagerOreo;
import mirror.android.app.ActivityThread;

/**
 * A manifest-backed pool of real Android isolated processes.
 *
 * The first milestone intentionally exposes only a read-only feasibility probe. Guest Service
 * lifecycle commands are added only after the device proves that Shopee code and native libraries
 * can be loaded in this security domain.
 */
public class StubIsolatedService extends Service {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Service guestService;
    private Application guestApplication;
    private final IIsolatedGuestWorker.Stub binder = new IIsolatedGuestWorker.Stub() {
        @Override
        public Bundle probe(String packageName, String serviceClassName, String nativeLibraryName,
                boolean loadNativeLibrary) {
            Bundle result = new Bundle();
            result.putInt("pid", Process.myPid());
            result.putInt("uid", Process.myUid());
            result.putInt("gid", Os.getgid());
            result.putString("selinux", readFirstLine("/proc/self/attr/current"));
            result.putString("process", currentProcessName());
            result.putBoolean("isolatedUid", isIsolatedUid(Process.myUid()));
            try {
                Context packageContext = createPackageContext(packageName,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                ApplicationInfo info = packageContext.getApplicationInfo();
                result.putString("sourceDir", info.sourceDir);
                result.putStringArray("splitSourceDirs", info.splitSourceDirs);
                result.putString("nativeLibraryDir", info.nativeLibraryDir);
                result.putString("classLoader", packageContext.getClassLoader().getClass().getName());
                Class<?> serviceClass = Class.forName(serviceClassName, false,
                        packageContext.getClassLoader());
                result.putString("serviceClass", serviceClass.getName());
                result.putBoolean("codeLoaded", true);
                if (loadNativeLibrary) {
                    File library = new File(info.nativeLibraryDir, nativeLibraryName);
                    result.putString("nativeLibrary", library.getAbsolutePath());
                    System.load(library.getAbsolutePath());
                    result.putBoolean("nativeLoaded", true);
                }
            } catch (Throwable error) {
                result.putString("errorClass", error.getClass().getName());
                result.putString("errorMessage", String.valueOf(error.getMessage()));
            }
            return result;
        }

        @Override
        public Bundle createGuestService(android.content.pm.ServiceInfo requestedInfo) {
            return callOnMain(() -> createGuestServiceOnMain(requestedInfo));
        }

        @Override
        public IBinder bindGuestService(Intent intent, boolean rebind) {
            return callOnMain(() -> {
                requireGuestService();
                if (rebind) {
                    guestService.onRebind(intent);
                    return null;
                }
                return guestService.onBind(intent);
            });
        }

        @Override
        public boolean unbindGuestService(Intent intent) {
            return callOnMain(() -> {
                requireGuestService();
                return guestService.onUnbind(intent);
            });
        }

        @Override
        public int startGuestService(Intent intent, int flags, int startId) {
            return callOnMain(() -> {
                requireGuestService();
                return guestService.onStartCommand(intent, flags, startId);
            });
        }

        @Override
        public void destroyGuestService() {
            callOnMain(() -> {
                destroyGuestServiceOnMain();
                return null;
            });
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyGuestServiceOnMain();
        }
        super.onDestroy();
    }

    private Bundle createGuestServiceOnMain(android.content.pm.ServiceInfo requestedInfo) {
        Bundle result = new Bundle();
        try {
            if (guestService != null) {
                result.putBoolean("created", true);
                result.putBoolean("reused", true);
                return result;
            }
            android.content.pm.ServiceInfo physicalInfo = getPackageManager().getServiceInfo(
                    new android.content.ComponentName(
                            requestedInfo.packageName, requestedInfo.name), 0);
            Context packageContext = createPackageContext(physicalInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            installGuestProcessIdentity(physicalInfo);
            String applicationClassName = physicalInfo.applicationInfo.className;
            if (applicationClassName == null || applicationClassName.isEmpty()) {
                applicationClassName = Application.class.getName();
            }
            Class<?> applicationClass = Class.forName(applicationClassName, true,
                    packageContext.getClassLoader());
            guestApplication = (Application) applicationClass.getDeclaredConstructor().newInstance();
            Method attachApplication = Application.class.getDeclaredMethod("attach", Context.class);
            attachApplication.setAccessible(true);
            attachApplication.invoke(guestApplication, packageContext);
            guestApplication.onCreate();

            Class<?> serviceClass = Class.forName(physicalInfo.name, true,
                    packageContext.getClassLoader());
            Object instance = serviceClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Service)) {
                throw new IllegalStateException(physicalInfo.name + " is not a Service");
            }
            Service service = (Service) instance;
            attachGuestService(service, packageContext, physicalInfo.name);
            service.onCreate();
            guestService = service;
            result.putBoolean("created", true);
            result.putInt("pid", Process.myPid());
            result.putInt("uid", Process.myUid());
            result.putString("class", service.getClass().getName());
            result.putString("package", service.getPackageName());
            result.putString("application", guestApplication.getClass().getName());
        } catch (Throwable error) {
            result.putBoolean("created", false);
            result.putString("errorClass", error.getClass().getName());
            result.putString("errorMessage", String.valueOf(error.getMessage()));
            result.putString("errorStack", android.util.Log.getStackTraceString(error));
        }
        return result;
    }

    private void attachGuestService(Service service, Context context, String className)
            throws Throwable {
        Object activityThread = ActivityThread.currentActivityThread.call();
        Object activityManager = ActivityManagerOreo.getService.call();
        Method attach = null;
        for (Method method : Service.class.getDeclaredMethods()) {
            if ("attach".equals(method.getName()) && method.getParameterTypes().length == 6) {
                attach = method;
                break;
            }
        }
        if (attach == null) {
            throw new NoSuchMethodException("Service.attach(Context, ActivityThread, ...)");
        }
        attach.setAccessible(true);
        attach.invoke(service, context, activityThread, className, new android.os.Binder(),
                guestApplication, activityManager);
    }

    private void installGuestProcessIdentity(android.content.pm.ServiceInfo serviceInfo) {
        String processName = serviceInfo.processName;
        VirtualRuntime.setupRuntime(processName, serviceInfo.applicationInfo);
        Object activityThread = ActivityThread.currentActivityThread.call();
        Object boundApp = ActivityThread.mBoundApplication.get(activityThread);
        if (boundApp != null) {
            ActivityThread.AppBindData.processName.set(boundApp, processName);
            ActivityThread.AppBindData.appInfo.set(boundApp, serviceInfo.applicationInfo);
        }
    }

    private void requireGuestService() {
        if (guestService == null) {
            throw new IllegalStateException("guest Service has not been created");
        }
    }

    private void destroyGuestServiceOnMain() {
        Service service = guestService;
        guestService = null;
        if (service != null) {
            try {
                service.onDestroy();
            } catch (Throwable ignored) {
            }
        }
        guestApplication = null;
    }

    private <T> T callOnMain(Callable<T> callable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return callable.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
        FutureTask<T> task = new FutureTask<>(callable);
        mainHandler.post(task);
        return IsolatedWorkerCallGate.await(task);
    }

    private static boolean isIsolatedUid(int uid) {
        int appId = uid % 100000;
        return appId >= 90000 && appId <= 99999;
    }

    private static String currentProcessName() {
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readFirstLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (Throwable error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    public static class C0 extends StubIsolatedService {}
    public static class C1 extends StubIsolatedService {}
    public static class C2 extends StubIsolatedService {}
    public static class C3 extends StubIsolatedService {}
    public static class C4 extends StubIsolatedService {}
    public static class C5 extends StubIsolatedService {}
    public static class C6 extends StubIsolatedService {}
    public static class C7 extends StubIsolatedService {}
    public static class C8 extends StubIsolatedService {}
    public static class C9 extends StubIsolatedService {}
    public static class C10 extends StubIsolatedService {}
    public static class C11 extends StubIsolatedService {}
    public static class C12 extends StubIsolatedService {}
    public static class C13 extends StubIsolatedService {}
    public static class C14 extends StubIsolatedService {}
    public static class C15 extends StubIsolatedService {}
    public static class C16 extends StubIsolatedService {}
    public static class C17 extends StubIsolatedService {}
    public static class C18 extends StubIsolatedService {}
    public static class C19 extends StubIsolatedService {}
    public static class C20 extends StubIsolatedService {}
    public static class C21 extends StubIsolatedService {}
    public static class C22 extends StubIsolatedService {}
    public static class C23 extends StubIsolatedService {}
    public static class C24 extends StubIsolatedService {}
    public static class C25 extends StubIsolatedService {}
    public static class C26 extends StubIsolatedService {}
    public static class C27 extends StubIsolatedService {}
    public static class C28 extends StubIsolatedService {}
    public static class C29 extends StubIsolatedService {}
    public static class C30 extends StubIsolatedService {}
    public static class C31 extends StubIsolatedService {}
    public static class C32 extends StubIsolatedService {}
    public static class C33 extends StubIsolatedService {}
    public static class C34 extends StubIsolatedService {}
    public static class C35 extends StubIsolatedService {}
    public static class C36 extends StubIsolatedService {}
    public static class C37 extends StubIsolatedService {}
    public static class C38 extends StubIsolatedService {}
    public static class C39 extends StubIsolatedService {}
    public static class C40 extends StubIsolatedService {}
    public static class C41 extends StubIsolatedService {}
    public static class C42 extends StubIsolatedService {}
    public static class C43 extends StubIsolatedService {}
    public static class C44 extends StubIsolatedService {}
    public static class C45 extends StubIsolatedService {}
    public static class C46 extends StubIsolatedService {}
    public static class C47 extends StubIsolatedService {}
    public static class C48 extends StubIsolatedService {}
    public static class C49 extends StubIsolatedService {}
}
