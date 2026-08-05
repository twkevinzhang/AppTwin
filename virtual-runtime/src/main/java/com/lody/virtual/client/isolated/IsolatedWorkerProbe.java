package com.lody.virtual.client.isolated;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.content.pm.ServiceInfo;

/** One-shot device feasibility probe used by the runtimeProbe build. */
public final class IsolatedWorkerProbe {
    private static final String TAG = "VA-IsolatedProbe";

    private IsolatedWorkerProbe() {
    }

    public static void run(Context context, String packageName, String serviceClassName,
            String nativeLibraryName, boolean loadNativeLibrary, boolean createGuestService) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent().setComponent(IsolatedWorkerSlots.component(appContext, 0));
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IIsolatedGuestWorker worker = IIsolatedGuestWorker.Stub.asInterface(service);
                    Bundle result = worker.probe(packageName, serviceClassName, nativeLibraryName,
                            loadNativeLibrary);
                    Log.i(TAG, "summary pid=" + result.getInt("pid")
                            + " uid=" + result.getInt("uid")
                            + " isolatedUid=" + result.getBoolean("isolatedUid")
                            + " selinux=" + result.getString("selinux")
                            + " process=" + result.getString("process")
                            + " codeLoaded=" + result.getBoolean("codeLoaded")
                            + " nativeLoaded=" + result.getBoolean("nativeLoaded")
                            + " error=" + result.getString("errorClass") + ":"
                            + result.getString("errorMessage"));
                    Log.i(TAG, "result=" + bundleToString(result));
                    if (createGuestService) {
                        ServiceInfo serviceInfo = appContext.getPackageManager().getServiceInfo(
                                new ComponentName(packageName, serviceClassName), 0);
                        Bundle createResult = worker.createGuestService(serviceInfo);
                        Log.i(TAG, "guest-create=" + bundleToString(createResult));
                        if (createResult.getBoolean("created")) {
                            Intent guestIntent = new Intent().setComponent(
                                    new ComponentName(packageName, serviceClassName));
                            IBinder guestBinder = worker.bindGuestService(guestIntent, false);
                            Log.i(TAG, "guest-bind binder=" + guestBinder);
                            Log.i(TAG, "guest-unbind doRebind="
                                    + worker.unbindGuestService(guestIntent));
                            worker.destroyGuestService();
                            Log.i(TAG, "guest-destroy complete");
                        }
                    }
                } catch (RemoteException error) {
                    Log.e(TAG, "worker probe failed", error);
                } catch (Throwable error) {
                    Log.e(TAG, "guest lifecycle probe failed", error);
                } finally {
                    try {
                        appContext.unbindService(this);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "worker disconnected before probe completed: " + name);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Log.e(TAG, "worker binding died: " + name);
            }
        };
        boolean bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bind slot=0 requested=" + bound + " component=" + intent.getComponent());
    }

    private static String bundleToString(Bundle bundle) {
        StringBuilder value = new StringBuilder("{");
        boolean first = true;
        for (String key : bundle.keySet()) {
            if (!first) value.append(", ");
            first = false;
            Object entry = bundle.get(key);
            if (entry instanceof String[]) {
                entry = java.util.Arrays.toString((String[]) entry);
            }
            value.append(key).append('=').append(entry);
        }
        return value.append('}').toString();
    }
}
