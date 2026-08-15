package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;

import com.lody.virtual.client.stub.StubKeepAliveService;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.PendingResultData;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Temporarily promotes cloned LINE while its FCM receiver is dispatched.
 *
 * <p>Android kills a cached frozen process when the virtual activity manager makes a synchronous
 * Binder call into it. Binding the slot's host service first makes Android thaw the process; the
 * binding is released after the virtual broadcast finishes or reaches a bounded timeout.</p>
 */
final class LinePushProcessGuard {
    private static final String TAG = LinePushProcessGuard.class.getSimpleName();
    private static final String STUB_CLASS = StubKeepAliveService.class.getName();
    private static final long BIND_TIMEOUT_MILLIS = 3_000L;
    private static final long RELEASE_GRACE_MILLIS = 3_000L;
    private static final long MAX_HOLD_MILLIS = 15_000L;

    private final Context context;
    private final Handler handler;
    private final Map<ProcessRecord, Lease> leases = new IdentityHashMap<>();
    private final Map<IBinder, Lease> leasesByToken = new HashMap<>();

    LinePushProcessGuard(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    boolean protectAndDispatch(ProcessRecord process, String action,
                               PendingResultData result, Runnable dispatch) {
        String packageName = process.info == null ? null : process.info.packageName;
        if (result == null || result.mToken == null
                || !LinePushBroadcastPolicy.shouldProtect(
                packageName, process.processName, action,
                process.vpid, process.osIsolatedWorker)) {
            return false;
        }

        Lease lease;
        LinePushProcessProtection.EnqueueAction enqueueAction;
        synchronized (this) {
            lease = leases.get(process);
            if (lease == null) {
                lease = new Lease(process);
                leases.put(process, lease);
            }
            handler.removeCallbacks(lease.releaseTask);
            enqueueAction = lease.protection.enqueue(
                    result.mToken, dispatch, result::finish);
            leasesByToken.put(result.mToken, lease);
        }

        if (enqueueAction == LinePushProcessProtection.EnqueueAction.START_BIND) {
            beginBinding(lease);
        } else if (enqueueAction == LinePushProcessProtection.EnqueueAction.DISPATCH_NOW) {
            dispatch.run();
        }
        return true;
    }

    void complete(IBinder token) {
        if (token == null) {
            return;
        }
        synchronized (this) {
            Lease lease = leasesByToken.remove(token);
            if (lease == null || !lease.protection.complete(token)) {
                return;
            }
            handler.removeCallbacks(lease.releaseTask);
            handler.postDelayed(lease.releaseTask, RELEASE_GRACE_MILLIS);
        }
    }

    void release(ProcessRecord process) {
        Lease lease;
        List<Runnable> aborts;
        synchronized (this) {
            lease = leases.remove(process);
            if (lease == null) {
                return;
            }
            removeLeaseTokensLocked(lease);
            cancelTimersLocked(lease);
            aborts = lease.protection.terminate();
        }
        runAll(aborts);
        unbind(lease);
    }

    private void beginBinding(Lease lease) {
        handler.postDelayed(lease.bindTimeoutTask, BIND_TIMEOUT_MILLIS);
        Intent intent = new Intent().setComponent(componentName(context, lease.process.vpid));
        boolean bound;
        try {
            bound = context.bindService(intent, lease.connection, bindingFlags());
        } catch (RuntimeException error) {
            VLog.e(TAG, "Unable to thaw LINE push process slot=" + lease.process.vpid
                    + " error=" + error);
            bound = false;
        }
        synchronized (this) {
            if (leases.get(lease.process) == lease) {
                lease.bound = bound;
            }
        }
        if (!bound) {
            failBinding(lease, "bind-rejected");
        }
    }

    private void onConnected(Lease lease) {
        List<Runnable> dispatches;
        synchronized (this) {
            if (leases.get(lease.process) != lease) {
                return;
            }
            handler.removeCallbacks(lease.bindTimeoutTask);
            dispatches = lease.protection.onConnected();
            handler.removeCallbacks(lease.maxHoldTask);
            handler.postDelayed(lease.maxHoldTask, MAX_HOLD_MILLIS);
        }
        VLog.i(TAG, "line-push-thawed user=" + lease.process.userId
                + " slot=" + lease.process.vpid
                + " generation=" + lease.process.generation
                + " pending=" + dispatches.size());
        runAll(dispatches);
    }

    private void failBinding(Lease lease, String reason) {
        List<Runnable> aborts;
        synchronized (this) {
            if (leases.get(lease.process) != lease) {
                return;
            }
            leases.remove(lease.process);
            removeLeaseTokensLocked(lease);
            cancelTimersLocked(lease);
            aborts = lease.protection.terminate();
        }
        VLog.e(TAG, "line-push-thaw-failed user=" + lease.process.userId
                + " slot=" + lease.process.vpid + " reason=" + reason);
        runAll(aborts);
        unbind(lease);
    }

    private void releaseIfIdle(Lease lease) {
        synchronized (this) {
            if (leases.get(lease.process) != lease || !lease.protection.releaseIfIdle()) {
                return;
            }
            leases.remove(lease.process);
            cancelTimersLocked(lease);
        }
        VLog.i(TAG, "line-push-released user=" + lease.process.userId
                + " slot=" + lease.process.vpid
                + " generation=" + lease.process.generation);
        unbind(lease);
    }

    private void forceRelease(Lease lease, String reason) {
        List<Runnable> aborts;
        synchronized (this) {
            if (leases.get(lease.process) != lease) {
                return;
            }
            leases.remove(lease.process);
            removeLeaseTokensLocked(lease);
            cancelTimersLocked(lease);
            aborts = lease.protection.terminate();
        }
        VLog.w(TAG, "line-push-release-timeout user=" + lease.process.userId
                + " slot=" + lease.process.vpid + " reason=" + reason);
        runAll(aborts);
        unbind(lease);
    }

    private void removeLeaseTokensLocked(Lease lease) {
        for (Object token : lease.protection.tokens()) {
            leasesByToken.remove(token);
        }
    }

    private void cancelTimersLocked(Lease lease) {
        handler.removeCallbacks(lease.bindTimeoutTask);
        handler.removeCallbacks(lease.releaseTask);
        handler.removeCallbacks(lease.maxHoldTask);
    }

    private void unbind(Lease lease) {
        if (!lease.bound) {
            return;
        }
        lease.bound = false;
        try {
            context.unbindService(lease.connection);
        } catch (IllegalArgumentException ignored) {
            // Android already discarded the binding with the dead guest process.
        }
    }

    private static void runAll(List<Runnable> actions) {
        for (Runnable action : actions) {
            action.run();
        }
    }

    static int bindingFlags() {
        return Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
    }

    static ComponentName componentName(Context context, int slot) {
        return new ComponentName(context.getPackageName(), STUB_CLASS + "$C" + slot);
    }

    private final class Lease {
        final ProcessRecord process;
        final LinePushProcessProtection protection = new LinePushProcessProtection();
        final Runnable bindTimeoutTask = () -> failBinding(this, "bind-timeout");
        final Runnable releaseTask = () -> releaseIfIdle(this);
        final Runnable maxHoldTask = () -> forceRelease(this, "max-hold");
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                onConnected(Lease.this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                VLog.w(TAG, "line-push-disconnected user=" + process.userId
                        + " slot=" + process.vpid);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                failBinding(Lease.this, "binding-died");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                failBinding(Lease.this, "null-binding");
            }
        };
        boolean bound;

        Lease(ProcessRecord process) {
            this.process = process;
        }
    }
}
