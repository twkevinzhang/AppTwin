package com.lody.virtual.server.am;

import android.accounts.Account;
import android.content.*;
import android.os.*;
import com.lody.virtual.helper.compat.SyncAdapterRegistration;
import com.lody.virtual.helper.compat.SyncAdapterWire;
import com.lody.virtual.server.accounts.VAccountManagerService;
import java.util.*;
import java.util.concurrent.*;

/** Server-owned, bounded manual synchronization. Never accepts an endpoint or extras from callers. */
final class ScopedAccountSyncManager {
    interface Gateway { Target resolve(int user, ComponentName component); boolean isCurrent(Target target); }
    static final class Target {
        final int user;
        final ComponentName component;
        final ServiceRecord record;
        final ProcessRecord process;
        final long processGeneration, serviceGeneration, gateEpoch;
        final String authorizationToken;
        final IBinder endpoint;
        Target(int user, ComponentName component, ServiceRecord record, IBinder endpoint, long epoch, String token) {
            this.user = user; this.component = component; this.record = record; this.endpoint = endpoint;
            process = record.process; processGeneration = process.generation; serviceGeneration = record.generation;
            gateEpoch = epoch; authorizationToken = token;
        }
    }
    private final Gateway gateway;
    private final Object lock = new Object();
    private final Map<IBinder, Lease> leases = new HashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "VA-scoped-account-sync"); thread.setDaemon(true); return thread;
    });
    ScopedAccountSyncManager(Gateway gateway) { this.gateway = gateway; }

    private final class Lease {
        final Target target;
        final Account account;
        final long deadline = SystemClock.elapsedRealtime() + 120_000L;
        final IBinder context;
        final ISyncContext caller, forwarding;
        final IBinder.DeathRecipient death;
        ScheduledFuture<?> watchdog;
        volatile boolean completedByAdapter;
        Lease(Target target, Account account, IBinder context) {
            this.target = target; this.account = account; this.context = context;
            caller = ISyncContext.Stub.asInterface(context);
            death = () -> cancel(this);
            forwarding = new ISyncContext.Stub() {
                @Override public void sendHeartbeat() throws RemoteException {
                    if (Binder.getCallingPid() == target.process.pid && valid(Lease.this)) caller.sendHeartbeat();
                }
                @Override public void onFinished(SyncResult result) throws RemoteException {
                    if (Binder.getCallingPid() != target.process.pid || !valid(Lease.this) || !detach(Lease.this, true)) return;
                    android.util.Log.i("ScopedAccountSync", "finished user=" + target.user + " success=" + (result != null && !result.hasError()));
                    caller.onFinished(result);
                }
            };
        }
    }

    boolean start(int user, ComponentName component, Account account, String authority, IBinder context) {
        if (account == null || context == null || authority == null
                || !SyncAdapterRegistration.matches(user, component, account.type, authority) || !accountExists(user, account)) return false;
        Target target = gateway.resolve(user, component);
        if (target == null) return false;
        Lease lease = new Lease(target, account, context);
        synchronized (lock) {
            if (leases.size() >= 4 || leases.containsKey(context)) return false;
            leases.put(context, lease);
        }
        try {
            context.linkToDeath(lease.death, 0);
            if (!valid(lease)) { cancel(lease); return false; }
            synchronized (lock) {
                if (leases.get(context) != lease) return false;
                lease.watchdog = worker.scheduleAtFixedRate(() -> { if (!valid(lease)) cancel(lease); }, 1, 1, TimeUnit.SECONDS);
            }
            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            android.util.Log.i("ScopedAccountSync", "start user=" + user + " component=" + component);
            long identity = Binder.clearCallingIdentity();
            try { SyncAdapterWire.startManagerBridge(target.endpoint, lease.forwarding.asBinder(), authority, account, extras, Build.VERSION.SDK_INT); }
            finally { Binder.restoreCallingIdentity(identity); }
            // A cancelled/removed lease may lose before the one-way start was registered.
            if (!valid(lease)) {
                // Detach is the atomic terminal claim. A genuine quick finish may win it.
                cancel(lease);
                if (lease.completedByAdapter) return true;
                sendCancel(lease);
                return false;
            }
            return true;
        } catch (RemoteException | RuntimeException failure) { cancel(lease); return false; }
    }
    void cancel(int user, ComponentName component, IBinder context) {
        Lease lease;
        synchronized (lock) { lease = leases.get(context); }
        if (lease != null && lease.target.user == user && lease.target.component.equals(component)) cancel(lease);
    }
    void retire(ServiceRecord record) {
        List<Lease> owned = new ArrayList<>();
        synchronized (lock) { for (Lease lease : leases.values()) if (lease.target.record == record) owned.add(lease); }
        for (Lease lease : owned) cancel(lease);
    }
    private boolean accountExists(int user, Account account) {
        long identity = Binder.clearCallingIdentity();
        try {
            for (Account existing : VAccountManagerService.get().getAccounts(user, account.type)) if (existing.equals(account)) return true;
            return false;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    private boolean attached(Lease lease) { synchronized (lock) { return leases.get(lease.context) == lease; } }
    private boolean valid(Lease lease) {
        return attached(lease) && ScopedSyncLeasePolicy.valid(lease.target.user, lease.target.process.userId,
                lease.target.processGeneration, lease.target.process.generation, gateway.isCurrent(lease.target),
                accountExists(lease.target.user, lease.account), true, SystemClock.elapsedRealtime(), lease.deadline);
    }
    private boolean detach(Lease lease, boolean completedByAdapter) {
        synchronized (lock) {
            if (leases.get(lease.context) != lease) return false;
            leases.remove(lease.context);
            lease.completedByAdapter = completedByAdapter;
            if (lease.watchdog != null) lease.watchdog.cancel(false);
        }
        lease.context.unlinkToDeath(lease.death, 0);
        return true;
    }
    private void cancel(Lease lease) { if (detach(lease, false)) worker.execute(() -> sendCancel(lease)); }
    private void sendCancel(Lease lease) {
        long identity = Binder.clearCallingIdentity();
        try { SyncAdapterWire.cancelManagerBridge(lease.target.endpoint, lease.forwarding.asBinder(), Build.VERSION.SDK_INT); }
        catch (RemoteException | RuntimeException ignored) { }
        finally { Binder.restoreCallingIdentity(identity); }
    }
}
