package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Binder;
import android.os.SystemClock;

import com.lody.virtual.client.stub.StubKeepAliveService;
import com.lody.virtual.client.stub.VASettings;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.PendingResultData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Bounded, generation-aware static broadcast dispatcher.
 *
 * <p>A short host binding thaws the target slot before an oneway IVClient call. Exactly one
 * receiver is in flight per guest process; completion is driven by the guest's real
 * PendingResult.finish(), including goAsync().</p>
 */
final class StaticBroadcastDispatcher {
    static final int PER_PROCESS_LIMIT = 32;
    static final int GLOBAL_LIMIT = 256;
    static final long BIND_TIMEOUT_MILLIS = 3_000L;
    static final long HARD_DEADLINE_MILLIS = 8_000L;
    private static final long RELEASE_GRACE_MILLIS = 250L;
    private static final String TAG = StaticBroadcastDispatcher.class.getSimpleName();
    private static final String STUB_CLASS = StubKeepAliveService.class.getName();

    interface OwnerValidator {
        boolean isCurrentOwner(ProcessRecord process);
    }

    interface CompletionSink {
        void finish(PendingResultData original, PendingResultData updated, String reason);
    }

    private final Context context;
    private final Handler handler;
    private final OwnerValidator validator;
    private final CompletionSink completionSink;
    private final BroadcastDispatchQueue<Request> queue =
            new BroadcastDispatchQueue<>(PER_PROCESS_LIMIT, GLOBAL_LIMIT);
    private final Map<ProcessRecord, Lease> leases = new IdentityHashMap<>();
    private final CompletionOwnershipRegistry<ProcessRecord> completionOwners =
            new CompletionOwnershipRegistry<>();
    private final AtomicLong nextToken = new AtomicLong(1L);

    StaticBroadcastDispatcher(Context context, Handler handler, OwnerValidator validator,
            CompletionSink completionSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.validator = validator;
        this.completionSink = completionSink;
    }

    boolean enqueue(ProcessRecord process, ActivityInfo info, Intent intent,
            PendingResultData result, BooleanSupplier dispatchPermit) {
        if (process == null || info == null || intent == null || result == null
                || dispatchPermit == null || result.mToken == null
                || !validator.isCurrentOwner(process)
                || (process.lifecycle.state() != ProcessLifecycle.State.STARTING
                    && process.lifecycle.state() != ProcessLifecycle.State.READY)) {
            finish(result, null, "target-not-ready");
            return false;
        }
        long token = nextDispatchToken();
        Request request = new Request(token, process.generation, info, new Intent(intent), result,
                result.forGuestDispatch(new Binder()),
                SystemClock.uptimeMillis() + HARD_DEADLINE_MILLIS, dispatchPermit);
        BroadcastDispatchQueue.EnqueueResult accepted =
                queue.enqueue(process, process.generation, token, request);
        if (accepted != BroadcastDispatchQueue.EnqueueResult.ACCEPTED) {
            finish(result, null, "queue-" + accepted.name().toLowerCase());
            return false;
        }
        completionOwners.register(request.guestResult.mToken, process,
                request.token, request.generation);
        synchronized (this) {
            if (!leases.containsKey(process)) {
                leases.put(process, new Lease(process));
            }
        }
        request.timeoutTask = () -> timeoutOnHandler(
                process, request.generation, request.token);
        handler.postDelayed(request.timeoutTask, HARD_DEADLINE_MILLIS);
        handler.post(() -> ensureBindingAndDispatch(process));
        return true;
    }

    void complete(long generation, long token, PendingResultData updated) {
        handler.post(() -> {
            Object capability = updated == null ? null : updated.mToken;
            ProcessRecord process = completionOwners.take(
                    capability, token, generation);
            if (process == null) {
                VLog.w(TAG, "Ignoring late, duplicate, or forged broadcast ACK token="
                        + token + " generation=" + generation);
                return;
            }
            completeOnHandler(process, generation, token, updated);
        });
    }

    void onProcessReady(ProcessRecord process) {
        if (process != null) handler.post(() -> ensureBindingAndDispatch(process));
    }

    void cancelProcess(ProcessRecord process, String reason) {
        if (process == null) return;
        handler.post(() -> cancelProcessOnHandler(process, process.generation, reason));
    }

    void cancelPackageUser(String packageName, int userId, String reason) {
        if (packageName == null) return;
        handler.post(() -> {
            List<ProcessRecord> matches = new ArrayList<>();
            synchronized (StaticBroadcastDispatcher.this) {
                for (ProcessRecord process : leases.keySet()) {
                    if ((userId < 0 || process.userId == userId)
                            && process.pkgList.contains(packageName)) {
                        matches.add(process);
                    }
                }
            }
            for (ProcessRecord process : matches) {
                cancelProcessOnHandler(process, process.generation, reason);
            }
        });
    }

    void cancelUser(int userId, String reason) {
        handler.post(() -> cancelMatching(null, userId, reason));
    }

    void cancelAll(String reason) {
        handler.post(() -> cancelMatching(null, -1, reason));
    }

    synchronized int pendingCount() {
        return queue.size();
    }

    private void cancelMatching(String packageName, int userId, String reason) {
        List<ProcessRecord> matches = new ArrayList<>();
        synchronized (this) {
            for (ProcessRecord process : leases.keySet()) {
                if ((userId < 0 || process.userId == userId)
                        && (packageName == null || process.pkgList.contains(packageName))) {
                    matches.add(process);
                }
            }
        }
        for (ProcessRecord process : matches) {
            cancelProcessOnHandler(process, process.generation, reason);
        }
    }

    private void ensureBindingAndDispatch(ProcessRecord process) {
        if (!validator.isCurrentOwner(process)) {
            cancelProcessOnHandler(process, process.generation, "stale-owner");
            return;
        }
        Lease lease;
        boolean beginBinding = false;
        synchronized (this) {
            lease = leases.get(process);
            if (lease == null) return;
            handler.removeCallbacks(lease.releaseTask);
        }
        if (process.lifecycle.state() == ProcessLifecycle.State.STARTING) return;
        if (process.lifecycle.state() != ProcessLifecycle.State.READY) {
            cancelProcessOnHandler(process, process.generation, "owner-not-ready");
            return;
        }
        synchronized (this) {
            if (!lease.connected && !lease.bindingStarted) {
                lease.bindingStarted = true;
                beginBinding = true;
            }
        }
        if (lease.connected) {
            dispatchNext(lease);
        } else if (beginBinding) {
            beginBinding(lease);
        }
    }

    private void beginBinding(Lease lease) {
        handler.postDelayed(lease.bindTimeoutTask, BIND_TIMEOUT_MILLIS);
        Intent intent = new Intent().setComponent(componentName(context, lease.process.vpid));
        boolean bound;
        try {
            bound = context.bindService(intent, lease.connection, bindingFlags());
        } catch (RuntimeException error) {
            VLog.e(TAG, "Unable to thaw broadcast target slot=" + lease.process.vpid
                    + " errorType=" + error.getClass().getSimpleName());
            bound = false;
        }
        synchronized (this) {
            if (leases.get(lease.process) == lease) lease.bound = bound;
        }
        if (!bound) cancelProcessOnHandler(
                lease.process, lease.process.generation, "bind-rejected");
    }

    private void onConnected(Lease lease) {
        synchronized (this) {
            if (leases.get(lease.process) != lease) return;
            lease.connected = true;
            handler.removeCallbacks(lease.bindTimeoutTask);
        }
        if (!validator.isCurrentOwner(lease.process)
                || lease.process.lifecycle.state() != ProcessLifecycle.State.READY) {
            cancelProcessOnHandler(lease.process, lease.process.generation, "stale-after-bind");
            return;
        }
        dispatchNext(lease);
    }

    private void dispatchNext(Lease lease) {
        if (!lease.connected || !validator.isCurrentOwner(lease.process)
                || lease.process.lifecycle.state() != ProcessLifecycle.State.READY) {
            cancelProcessOnHandler(lease.process, lease.process.generation, "owner-not-ready");
            return;
        }
        BroadcastDispatchQueue.Item<Request> item =
                queue.takeNext(lease.process, lease.process.generation);
        if (item == null) {
            if (queue.ownerSize(lease.process) == 0) scheduleRelease(lease);
            return;
        }
        Request request = item.value;
        if (!request.dispatchPermit.getAsBoolean()) {
            completeOnHandler(lease.process, request.generation, request.token,
                    null, "dispatch-permit-revoked");
            return;
        }
        if (request.deadlineUptimeMillis <= SystemClock.uptimeMillis()) {
            timeoutOnHandler(lease.process, request.generation, request.token);
            return;
        }
        try {
            LinePushDeliveryDiagnostics.checkpoint(request.result,
                    "target-ready user=" + lease.process.userId);
            LinePushDeliveryDiagnostics.checkpoint(request.result, "schedule-receiver");
            lease.process.client.scheduleReceiver(request.info.processName,
                    ComponentUtils.toComponentName(request.info), request.intent,
                    request.guestResult,
                    request.token, request.generation);
        } catch (Throwable error) {
            VLog.w(TAG, "Unable to enqueue oneway static receiver package="
                    + request.info.packageName + " errorType="
                    + error.getClass().getSimpleName());
            completeOnHandler(lease.process, request.generation, request.token,
                    null, "schedule-failed");
        }
    }

    private void completeOnHandler(ProcessRecord process, long generation, long token,
            PendingResultData updated) {
        completeOnHandler(process, generation, token, updated, "completed");
    }

    private void completeOnHandler(ProcessRecord process, long generation, long token,
            PendingResultData updated, String reason) {
        BroadcastDispatchQueue.Item<Request> completed =
                queue.complete(process, generation, token);
        if (completed == null) {
            return;
        }
        completionOwners.revoke(completed.value.guestResult.mToken);
        handler.removeCallbacks(completed.value.timeoutTask);
        Lease lease;
        synchronized (this) {
            lease = leases.get(process);
        }
        if (!"completed".equals(reason)) {
            try {
                process.client.cancelReceiver(token, generation);
            } catch (Throwable ignored) {
            }
        }
        finish(completed.value.result, updated, reason);
        if (lease != null) dispatchNext(lease);
    }

    private void timeoutOnHandler(ProcessRecord process, long generation, long token) {
        BroadcastDispatchQueue.Item<Request> timedOut =
                queue.remove(process, generation, token);
        if (timedOut == null) return;
        completionOwners.revoke(timedOut.value.guestResult.mToken);
        try {
            process.client.cancelReceiver(token, generation);
        } catch (Throwable ignored) {
        }
        finish(timedOut.value.result, null, "receiver-timeout");
        Lease lease;
        synchronized (this) {
            lease = leases.get(process);
        }
        if (lease != null) dispatchNext(lease);
    }

    private void cancelProcessOnHandler(ProcessRecord process, long generation, String reason) {
        List<BroadcastDispatchQueue.Item<Request>> cancelled = queue.cancel(process, generation);
        Lease lease;
        synchronized (this) {
            lease = leases.remove(process);
            if (lease != null) {
                handler.removeCallbacks(lease.bindTimeoutTask);
                handler.removeCallbacks(lease.releaseTask);
            }
        }
        for (BroadcastDispatchQueue.Item<Request> item : cancelled) {
            completionOwners.revoke(item.value.guestResult.mToken);
            handler.removeCallbacks(item.value.timeoutTask);
            try {
                process.client.cancelReceiver(item.token, generation);
            } catch (Throwable ignored) {
            }
            finish(item.value.result, null, reason);
        }
        unbind(lease);
    }

    private void scheduleRelease(Lease lease) {
        synchronized (this) {
            if (leases.get(lease.process) != lease) return;
            handler.removeCallbacks(lease.releaseTask);
            handler.postDelayed(lease.releaseTask, RELEASE_GRACE_MILLIS);
        }
    }

    private void releaseIfIdle(Lease lease) {
        synchronized (this) {
            if (leases.get(lease.process) != lease || queue.ownerSize(lease.process) != 0) return;
            leases.remove(lease.process);
        }
        unbind(lease);
    }

    private void unbind(Lease lease) {
        if (lease == null || !lease.bound) return;
        try {
            context.unbindService(lease.connection);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void finish(PendingResultData original, PendingResultData updated, String reason) {
        LinePushDeliveryDiagnostics.finish(original == null ? null : original.mToken, reason);
        completionSink.finish(original, updated, reason);
    }

    private long nextDispatchToken() {
        long token = nextToken.getAndIncrement();
        if (token > 0) return token;
        synchronized (nextToken) {
            if (nextToken.get() <= 0) nextToken.set(2L);
            return 1L;
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

    private static final class Request {
        final long token;
        final long generation;
        final ActivityInfo info;
        final Intent intent;
        final PendingResultData result;
        final PendingResultData guestResult;
        final long deadlineUptimeMillis;
        final BooleanSupplier dispatchPermit;
        Runnable timeoutTask;

        Request(long token, long generation, ActivityInfo info, Intent intent,
                PendingResultData result, PendingResultData guestResult,
                long deadlineUptimeMillis, BooleanSupplier dispatchPermit) {
            this.token = token;
            this.generation = generation;
            this.info = info;
            this.intent = intent;
            this.result = result;
            this.guestResult = guestResult;
            this.deadlineUptimeMillis = deadlineUptimeMillis;
            this.dispatchPermit = dispatchPermit;
        }
    }

    /** Capability registry: all three values must match before ownership is consumed. */
    static final class CompletionOwnershipRegistry<O> {
        private final Map<Object, Ownership<O>> owners = new HashMap<>();

        synchronized void register(Object capability, O owner, long token, long generation) {
            if (capability == null || owner == null || token <= 0 || generation < 0) {
                throw new IllegalArgumentException("valid completion ownership is required");
            }
            if (owners.put(capability,
                    new Ownership<>(owner, token, generation)) != null) {
                throw new IllegalStateException("completion capability already registered");
            }
        }

        synchronized O take(Object capability, long token, long generation) {
            Ownership<O> ownership = owners.get(capability);
            if (ownership == null || ownership.token != token
                    || ownership.generation != generation) {
                return null;
            }
            owners.remove(capability);
            return ownership.owner;
        }

        synchronized void revoke(Object capability) {
            if (capability != null) owners.remove(capability);
        }

        synchronized int size() {
            return owners.size();
        }

        private static final class Ownership<O> {
            final O owner;
            final long token;
            final long generation;

            Ownership(O owner, long token, long generation) {
                this.owner = owner;
                this.token = token;
                this.generation = generation;
            }
        }
    }

    private final class Lease {
        final ProcessRecord process;
        final Runnable bindTimeoutTask;
        final Runnable releaseTask;
        final ServiceConnection connection;
        boolean bound;
        boolean connected;
        boolean bindingStarted;
        Lease(ProcessRecord process) {
            this.process = process;
            bindTimeoutTask = () -> cancelProcessOnHandler(
                    process, process.generation, "bind-timeout");
            releaseTask = () -> releaseIfIdle(this);
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    handler.post(() -> onConnected(Lease.this));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    handler.post(() -> cancelProcessOnHandler(
                            process, process.generation, "binding-disconnected"));
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    handler.post(() -> cancelProcessOnHandler(
                            process, process.generation, "binding-died"));
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    handler.post(() -> cancelProcessOnHandler(
                            process, process.generation, "null-binding"));
                }
            };
        }
    }
}
