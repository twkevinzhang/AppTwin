package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.lody.virtual.client.stub.StubKeepAliveService;
import com.lody.virtual.client.stub.VASettings;
import com.lody.virtual.helper.utils.VLog;

import java.util.IdentityHashMap;
import java.util.Map;

/** Bounded, ref-counted thaw gate for guest IPC that must synchronously return a value. */
final class GuestProcessThawCoordinator {
    static final long TIMEOUT_MILLIS = 3_000L;
    private static final String TAG = GuestProcessThawCoordinator.class.getSimpleName();
    private static final String STUB_CLASS = StubKeepAliveService.class.getName();

    interface OwnerValidator {
        boolean isCurrentReadyOwner(ProcessRecord process, long generation);
    }

    interface Operation<T> {
        T run() throws Exception;
    }

    private final Context context;
    private final OwnerValidator validator;
    private final Map<ProcessRecord, Lease> leases = new IdentityHashMap<>();

    GuestProcessThawCoordinator(Context context, OwnerValidator validator) {
        this.context = context.getApplicationContext();
        this.validator = validator;
    }

    <T> T execute(ProcessRecord process, Operation<T> operation, T failureValue) {
        if (process == null || operation == null || process.osIsolatedWorker) return failureValue;
        // ServiceConnection callbacks are delivered on the main looper. Waiting there would
        // prevent the very callback that makes this bounded lease usable.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            VLog.w(TAG, "Refusing to wait for guest thaw on the main looper");
            return failureValue;
        }
        Lease lease;
        boolean startBinding = false;
        synchronized (this) {
            lease = leases.get(process);
            if (lease == null) {
                lease = new Lease(process,
                        SystemClock.uptimeMillis() + TIMEOUT_MILLIS);
                leases.put(process, lease);
                startBinding = true;
            }
            lease.references++;
        }
        if (startBinding && !beginBinding(lease)) {
            cancel(process, "bind-rejected");
        }
        try {
            if (!awaitReady(lease)) return failureValue;
            if (!validator.isCurrentReadyOwner(process, lease.generation)) return failureValue;
            return operation.run();
        } catch (Exception error) {
            VLog.w(TAG, "Guest IPC failed process=" + process.processName
                    + " generation=" + lease.generation
                    + " errorType=" + error.getClass().getSimpleName());
            return failureValue;
        } finally {
            release(lease);
        }
    }

    void onProcessReady(ProcessRecord process) {
        synchronized (this) {
            if (leases.containsKey(process)) notifyAll();
        }
    }

    void cancel(ProcessRecord process, String reason) {
        Lease lease;
        synchronized (this) {
            lease = leases.remove(process);
            if (lease == null) return;
            lease.cancelled = true;
            notifyAll();
        }
        VLog.d(TAG, "Cancelled guest thaw process=" + process.processName
                + " generation=" + lease.generation + " reason=" + reason);
        unbind(lease);
    }

    synchronized int activeLeaseCount() {
        return leases.size();
    }

    private boolean beginBinding(Lease lease) {
        Intent intent = new Intent().setComponent(componentName(context, lease.process.vpid));
        boolean bound;
        try {
            bound = context.bindService(intent, lease.connection, bindingFlags());
        } catch (RuntimeException error) {
            bound = false;
        }
        boolean stale;
        synchronized (this) {
            stale = leases.get(lease.process) != lease;
            if (!stale) {
                lease.bound = bound;
                if (!bound) lease.cancelled = true;
                notifyAll();
            }
        }
        if (stale && bound) {
            try {
                context.unbindService(lease.connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return bound && !stale;
    }

    private boolean awaitReady(Lease lease) {
        while (true) {
            synchronized (this) {
                if (lease.cancelled || leases.get(lease.process) != lease) return false;
                if (!lease.connected) {
                    long remaining = lease.deadlineUptimeMillis - SystemClock.uptimeMillis();
                    if (remaining <= 0) return false;
                    try {
                        wait(remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    continue;
                }
            }
            if (validator.isCurrentReadyOwner(lease.process, lease.generation)) return true;
            ProcessLifecycle.State state = lease.process.lifecycle.state();
            if (state == ProcessLifecycle.State.FAILED || state == ProcessLifecycle.State.DEAD) {
                return false;
            }
            synchronized (this) {
                long remaining = lease.deadlineUptimeMillis - SystemClock.uptimeMillis();
                if (remaining <= 0 || lease.cancelled) return false;
                try {
                    wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private void release(Lease lease) {
        boolean shouldUnbind = false;
        synchronized (this) {
            if (lease.references > 0) lease.references--;
            if (leases.get(lease.process) == lease && lease.references == 0) {
                leases.remove(lease.process);
                lease.cancelled = true;
                shouldUnbind = true;
                notifyAll();
            }
        }
        if (shouldUnbind) unbind(lease);
    }

    private void unbind(Lease lease) {
        synchronized (lease) {
            if (!lease.bound || lease.unbound) return;
            lease.unbound = true;
        }
        try {
            context.unbindService(lease.connection);
        } catch (IllegalArgumentException ignored) {
        }
    }

    static int bindingFlags() {
        return Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
    }

    static ComponentName componentName(Context context, int vpid) {
        if (vpid < 0 || vpid >= VASettings.STUB_COUNT) {
            throw new IllegalArgumentException("invalid virtual process slot " + vpid);
        }
        return new ComponentName(context.getPackageName(), STUB_CLASS + "$C" + vpid);
    }

    private final class Lease {
        final ProcessRecord process;
        final long generation;
        final long deadlineUptimeMillis;
        final ServiceConnection connection;
        int references;
        boolean bound;
        boolean connected;
        boolean cancelled;
        boolean unbound;

        Lease(ProcessRecord process, long deadlineUptimeMillis) {
            this.process = process;
            generation = process.generation;
            this.deadlineUptimeMillis = deadlineUptimeMillis;
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (GuestProcessThawCoordinator.this) {
                        if (leases.get(process) != Lease.this || cancelled) return;
                        connected = true;
                        GuestProcessThawCoordinator.this.notifyAll();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    cancel(process, "binding-disconnected");
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    cancel(process, "binding-died");
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    cancel(process, "null-binding");
                }
            };
        }
    }
}
