package com.lody.virtual.server;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.ServiceManagerNative;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.server.accounts.VAccountManagerService;
import com.lody.virtual.server.am.BroadcastSystem;
import com.lody.virtual.server.am.VActivityManagerService;
import com.lody.virtual.server.device.VDeviceManagerService;
import com.lody.virtual.server.interfaces.IServiceFetcher;
import com.lody.virtual.server.job.VJobSchedulerService;
import com.lody.virtual.server.location.VirtualLocationService;
import com.lody.virtual.server.notification.VNotificationManagerService;
import com.lody.virtual.server.pm.VAppManagerService;
import com.lody.virtual.server.pm.VPackageManagerService;
import com.lody.virtual.server.pm.VUserManagerService;
import com.lody.virtual.server.vs.VirtualStorageService;

/**
 * @author Lody
 */
public final class BinderProvider extends ContentProvider {

    private static final long ENGINE_READY_TIMEOUT_MS = 30_000L;
    private final EngineServiceReadiness mReadiness = new EngineServiceReadiness();
    private final ServiceFetcher mServiceFetcher = new ServiceFetcher();

    @Override
    public boolean onCreate() {
        android.util.Log.i("AppTwinStartup", "provider-oncreate-enter");
        if (!VirtualCore.get().isStartup()) {
            mReadiness.complete(false);
            return true;
        }
        // Android gives a new provider process a short publish deadline. Rebuilding activated
        // APK signatures can exceed it after reboot with cold storage caches. Publish this tiny
        // provider first, then initialize on the same main looper in the original order. Binder
        // clients wait on readiness off-main and never receive a partially scanned engine.
        // Front-of-queue keeps already queued Service/Job lifecycle messages behind bootstrap.
        boolean posted = new android.os.Handler(android.os.Looper.getMainLooper()).postAtFrontOfQueue(() -> {
            android.util.Log.i("AppTwinStartup", "provider-bootstrap-begin");
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                initializeServices();
                mReadiness.complete(true);
                android.util.Log.i("AppTwinEngine", "startup-ready durationMs="
                        + (android.os.SystemClock.elapsedRealtime() - startedAt));
            } catch (Throwable failure) {
                mReadiness.complete(false);
                android.util.Log.e("AppTwinEngine", "startup-failed", failure);
            }
        });
        if (!posted) mReadiness.complete(false);
        android.util.Log.i("AppTwinStartup", "provider-oncreate-return posted=" + posted);
        return true;
    }

    private void initializeServices() {
        Context context = getContext();
        VPackageManagerService.systemReady();
        addService(ServiceManagerNative.PACKAGE, VPackageManagerService.get());
        VActivityManagerService.systemReady(context);
        addService(ServiceManagerNative.ACTIVITY, VActivityManagerService.get());
        addService(ServiceManagerNative.USER, VUserManagerService.get());
        VAppManagerService.systemReady();
        addService(ServiceManagerNative.APP, VAppManagerService.get());
        BroadcastSystem.attach(VActivityManagerService.get(), VAppManagerService.get());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            addService(ServiceManagerNative.JOB, VJobSchedulerService.get());
        }
        VNotificationManagerService.systemReady(context);
        addService(ServiceManagerNative.NOTIFICATION, VNotificationManagerService.get());
        android.util.Log.i("AppTwinStartup", "provider-scan-begin");
        VAppManagerService.get().scanApps();
        android.util.Log.i("AppTwinStartup", "provider-scan-complete");
        VAccountManagerService.systemReady();
        addService(ServiceManagerNative.ACCOUNT, VAccountManagerService.get());
        addService(ServiceManagerNative.VS, VirtualStorageService.get());
        addService(ServiceManagerNative.DEVICE, VDeviceManagerService.get());
        addService(ServiceManagerNative.VIRTUAL_LOC, VirtualLocationService.get());
        VAppManagerService.get().completeTrustedPackageQuarantine();
        // Recovery must run after scanApps and every durable user-scoped service is ready. Running
        // it from VUserManagerService's constructor would miss persisted PackageSetting entries.
        VUserManagerService.get().recoverPartialUsers();
        android.util.Log.i("AppTwinStartup", "provider-recovery-complete");
        // Do not start guest services synchronously from ContentProvider.onCreate(). A guest stub
        // provider cannot publish until this BinderProvider returns, so doing so creates a
        // provider-start cycle. VActivityManagerService posts the initial reconciliation after
        // this main-loop turn; a later user-visible daemon start and its persisted repair job
        // provide independent retries without creating an FGS from provider startup.
    }


    private void addService(String name, IBinder service) {
        ServiceCache.addService(name, service);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("ensure_created".equals(method)) {
            requireReady();
            return null;
        }
        if ("@".equals(method)) {
            Bundle bundle = new Bundle();
            BundleCompat.putBinder(bundle, "_VA_|_binder_", mServiceFetcher);
            return bundle;
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private void requireReady() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // Local initialization resolves services through ServiceCache, never this remote
            // boundary. Fail instead of deadlocking if a future caller violates that contract.
            if (!mReadiness.await(0)) throw new IllegalStateException("Engine is initializing");
        } else if (!mReadiness.await(ENGINE_READY_TIMEOUT_MS)) {
            throw new IllegalStateException("Engine initialization did not complete");
        }
    }

    private class ServiceFetcher extends IServiceFetcher.Stub {
        @Override
        public IBinder getService(String name) throws RemoteException {
            requireReady();
            if (name != null) {
                return ServiceCache.getService(name);
            }
            return null;
        }

        @Override
        public void addService(String name, IBinder service) throws RemoteException {
            requireReady();
            com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
            if (name != null && service != null) {
                ServiceCache.addService(name, service);
            }
        }

        @Override
        public void removeService(String name) throws RemoteException {
            requireReady();
            com.lody.virtual.server.VirtualUserAccessPolicy.enforceHost();
            if (name != null) {
                ServiceCache.removeService(name);
            }
        }
    }
}
