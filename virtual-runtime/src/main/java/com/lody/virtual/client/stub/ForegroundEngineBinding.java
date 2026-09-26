package com.lody.virtual.client.stub;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.lody.virtual.client.core.VirtualCore;
import java.util.IdentityHashMap;
import java.util.Map;

/** OS-visible Activity-to-engine binding prevents a live foreground guest calling frozen VAMS. */
public final class ForegroundEngineBinding {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Activity, Lease> LEASES = new IdentityHashMap<>();
    private ForegroundEngineBinding() {}

    public static void started(Activity activity) {
        Lease lease = LEASES.get(activity);
        if (lease != null) {
            lease.stopped = false;
            MAIN.removeCallbacks(lease.release);
            return;
        }
        Lease created = new Lease(activity);
        Intent intent = new Intent().setComponent(new ComponentName(
                VirtualCore.get().getHostPkg(), ForegroundEngineBindingPolicy.SERVICE_CLASS));
        try {
            // Activity context supplies the real Activity token. Android lowers this dependency
            // when that Activity is hidden even if the guest misses its cleanup callback.
            created.bound = activity.bindService(intent, created.connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_ADJUST_WITH_ACTIVITY
                            | Context.BIND_IMPORTANT);
            if (created.bound) LEASES.put(activity, created);
            else android.util.Log.w("AppTwinEngine", "foreground-engine-bind-rejected");
        } catch (RuntimeException failure) {
            android.util.Log.w("AppTwinEngine", "foreground-engine-bind-failed type="
                    + failure.getClass().getSimpleName());
        }
    }

    public static void stopped(Activity activity) {
        Lease lease = LEASES.get(activity);
        if (lease == null || lease.stopped) return;
        lease.stopped = true;
        MAIN.postDelayed(lease.release, ForegroundEngineBindingPolicy.TRANSITION_MILLIS);
    }

    private static final class Lease {
        final Activity activity;
        boolean bound;
        boolean stopped;
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        final Runnable release;
        Lease(Activity activity) {
            this.activity = activity;
            release = () -> {
                if (!stopped || LEASES.get(activity) != this) return;
                LEASES.remove(activity);
                if (bound) {
                    bound = false;
                    try { activity.unbindService(connection); }
                    catch (RuntimeException ignored) { }
                }
            };
        }
    }
}
