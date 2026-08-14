package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.lody.virtual.client.stub.StubKeepAliveService;
import com.lody.virtual.client.stub.VASettings;
import com.lody.virtual.helper.utils.VLog;

import java.util.IdentityHashMap;
import java.util.Map;

/** Keeps the per-Group microG cloud-messaging transport runnable in the background. */
final class GmsBackgroundKeepAlive {
    private static final String TAG = GmsBackgroundKeepAlive.class.getSimpleName();
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS = "com.google.android.gms:persistent";
    private static final String STUB_CLASS = StubKeepAliveService.class.getName();

    private final Context context;
    private final Map<ProcessRecord, ServiceConnection> connections = new IdentityHashMap<>();

    GmsBackgroundKeepAlive(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized boolean retain(ProcessRecord process) {
        if (!shouldRetain(process.info == null ? null : process.info.packageName,
                process.processName, process.vpid, process.osIsolatedWorker)) {
            return false;
        }
        if (connections.containsKey(process)) {
            return true;
        }
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                VLog.i(TAG, "gms-push-retained user=" + process.userId
                        + " slot=" + process.vpid + " generation=" + process.generation);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                VLog.w(TAG, "gms-push-disconnected user=" + process.userId
                        + " slot=" + process.vpid + " generation=" + process.generation);
            }
        };
        Intent intent = new Intent().setComponent(componentName(context, process.vpid));
        boolean bound;
        try {
            bound = context.bindService(intent, connection, bindingFlags());
        } catch (RuntimeException error) {
            VLog.e(TAG, "Unable to retain microG push process slot=" + process.vpid
                    + " error=" + error);
            bound = false;
        }
        if (bound) {
            connections.put(process, connection);
        }
        return bound;
    }

    synchronized void release(ProcessRecord process) {
        ServiceConnection connection = connections.remove(process);
        if (connection == null) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Android already discarded the binding with the dead guest process.
        }
    }

    static boolean shouldRetain(String packageName, String processName, int vpid,
            boolean isolatedWorker) {
        return !isolatedWorker
                && vpid >= 0
                && vpid < VASettings.STUB_COUNT
                && GMS_PACKAGE.equals(packageName)
                && (GMS_PACKAGE.equals(processName)
                    || GMS_PERSISTENT_PROCESS.equals(processName));
    }

    static int bindingFlags() {
        return Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
    }

    static ComponentName componentName(Context context, int vpid) {
        return new ComponentName(
                context.getPackageName(),
                STUB_CLASS + "$C" + vpid);
    }
}
