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

/**
 * Keeps narrowly selected virtual processes runnable while Android has no physical visibility
 * into their guest-to-guest Binder dependencies.
 *
 * <p>The virtual activity manager routes Firefox's Gecko child-service bindings in-process. The
 * OS therefore sees tab/GPU/media/utility stub processes as cached even while the foreground
 * guest activity needs them. Keeping their matching host stub service bound prevents the freezer
 * from killing a synchronous Gecko Binder transaction. The binding is released with the guest
 * process generation, so it does not retain a closed child process.</p>
 */
final class GmsBackgroundKeepAlive {
    private static final String TAG = GmsBackgroundKeepAlive.class.getSimpleName();
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS = "com.google.android.gms:persistent";
    private static final String FIREFOX_PACKAGE = "org.mozilla.firefox";
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
                VLog.i(TAG, "guest-process-retained package=" + process.info.packageName
                        + " process=" + process.processName + " user=" + process.userId
                        + " slot=" + process.vpid + " generation=" + process.generation);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                VLog.w(TAG, "guest-process-disconnected package=" + process.info.packageName
                        + " process=" + process.processName + " user=" + process.userId
                        + " slot=" + process.vpid + " generation=" + process.generation);
            }
        };
        Intent intent = new Intent().setComponent(componentName(context, process.vpid));
        boolean bound;
        try {
            bound = context.bindService(intent, connection, bindingFlags());
        } catch (RuntimeException error) {
            VLog.e(TAG, "Unable to retain guest process package=" + process.info.packageName
                    + " process=" + process.processName + " slot=" + process.vpid
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
        if (isolatedWorker || vpid < 0 || vpid >= VASettings.STUB_COUNT) {
            return false;
        }
        return (GMS_PACKAGE.equals(packageName)
                && (GMS_PACKAGE.equals(processName)
                    || GMS_PERSISTENT_PROCESS.equals(processName)))
                || isFirefoxGeckoChildProcess(packageName, processName);
    }

    static boolean isFirefoxGeckoChildProcess(String packageName, String processName) {
        if (!FIREFOX_PACKAGE.equals(packageName) || processName == null) {
            return false;
        }
        return processName.startsWith(FIREFOX_PACKAGE + ":tab")
                || processName.startsWith(FIREFOX_PACKAGE + ":gpu")
                || processName.equals(FIREFOX_PACKAGE + ":media")
                || processName.startsWith(FIREFOX_PACKAGE + ":utility");
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
