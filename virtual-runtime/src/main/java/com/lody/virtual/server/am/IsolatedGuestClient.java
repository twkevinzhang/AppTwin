package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import com.lody.virtual.client.IVClient;
import com.lody.virtual.client.isolated.IIsolatedGuestWorker;
import com.lody.virtual.client.isolated.IsolatedWorkerSlots;
import com.lody.virtual.remote.PendingResultData;

/** IVClient endpoint backed by one real Android isolated-process worker slot. */
final class IsolatedGuestClient extends IVClient.Stub
        implements ServiceConnection, IBinder.DeathRecipient {

    interface Listener {
        void onReady(IsolatedGuestClient client, int pid, int uid);
        void onCreateFailed(IsolatedGuestClient client, String detail);
        void onWorkerDied(IsolatedGuestClient client);
        void onServicePublished(IBinder token, IBinder bindToken, Intent intent,
                IBinder service, int userId);
        void onServiceUnbound(IBinder token, IBinder bindToken, Intent intent,
                boolean doRebind, int userId);
        void onServiceStopped(IBinder token, int userId);
    }

    private final Context context;
    private final int slot;
    private final int userId;
    private final Listener listener;
    private final Object lock = new Object();
    private IIsolatedGuestWorker worker;
    private IBinder serviceToken;
    private ServiceInfo serviceInfo;
    private boolean bindRequested;
    private boolean closed;
    private boolean terminalCallbackSent;

    IsolatedGuestClient(Context context, int slot, int userId, Listener listener) {
        this.context = context.getApplicationContext();
        this.slot = slot;
        this.userId = userId;
        this.listener = listener;
    }

    boolean start() {
        synchronized (lock) {
            if (closed || bindRequested) return false;
            bindRequested = true;
        }
        Intent intent = new Intent().setComponent(IsolatedWorkerSlots.component(context, slot));
        boolean bound;
        try {
            bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            bound = false;
        }
        if (!bound) {
            signalCreateFailed("bindService returned false for isolated slot " + slot);
        }
        return bound;
    }

    boolean isEndpointActive() {
        synchronized (lock) {
            return !closed && !terminalCallbackSent;
        }
    }

    boolean isWorkerAlive() {
        synchronized (lock) {
            return !closed && worker != null && worker.asBinder().isBinderAlive()
                    && worker.asBinder().pingBinder();
        }
    }

    void close() {
        IIsolatedGuestWorker active;
        boolean shouldUnbind;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            active = worker;
            worker = null;
            shouldUnbind = bindRequested;
        }
        if (active != null) {
            try {
                active.asBinder().unlinkToDeath(this, 0);
            } catch (Throwable ignored) {
            }
            try {
                active.destroyGuestService();
            } catch (Throwable ignored) {
            }
        }
        if (shouldUnbind) {
            try {
                context.unbindService(this);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        IIsolatedGuestWorker connected = IIsolatedGuestWorker.Stub.asInterface(service);
        ServiceInfo pendingInfo;
        synchronized (lock) {
            if (closed) return;
            worker = connected;
            pendingInfo = serviceInfo;
        }
        try {
            service.linkToDeath(this, 0);
        } catch (RemoteException error) {
            signalWorkerDied();
            return;
        }
        if (pendingInfo != null) {
            createInWorker(connected, pendingInfo);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        signalWorkerDied();
    }

    @Override
    public void onBindingDied(ComponentName name) {
        signalWorkerDied();
    }

    @Override
    public void onNullBinding(ComponentName name) {
        signalCreateFailed("isolated worker returned a null binding for slot " + slot);
    }

    @Override
    public void binderDied() {
        signalWorkerDied();
    }

    @Override
    public void scheduleCreateService(IBinder token, ServiceInfo info, int processState) {
        IIsolatedGuestWorker active;
        synchronized (lock) {
            if (closed) return;
            serviceToken = token;
            serviceInfo = info;
            active = worker;
        }
        if (active != null) {
            createInWorker(active, info);
        }
    }

    private void createInWorker(IIsolatedGuestWorker active, ServiceInfo info) {
        try {
            Bundle result = active.createGuestService(info);
            if (result == null) {
                signalCreateFailed("isolated worker returned no create result for slot " + slot);
                return;
            }
            if (!result.getBoolean("created")) {
                signalCreateFailed(result.getString("errorClass") + ":"
                        + result.getString("errorMessage") + "\n"
                        + result.getString("errorStack"));
                return;
            }
            listener.onReady(this, result.getInt("pid", -1), result.getInt("uid", -1));
        } catch (Throwable error) {
            if (error instanceof RemoteException) {
                signalWorkerDied();
            } else {
                signalCreateFailed(error.getClass().getName() + ":" + error.getMessage());
            }
        }
    }

    @Override
    public void scheduleBindService(IBinder token, IBinder bindToken, Intent intent,
            boolean rebind, int processState, long bindSeq) throws RemoteException {
        IIsolatedGuestWorker active = requireWorker();
        IBinder binder = active.bindGuestService(intent, rebind);
        if (rebind && binder == null) {
            return;
        }
        listener.onServicePublished(token, bindToken, intent, binder, userId);
    }

    @Override
    public void scheduleUnbindService(IBinder token, IBinder bindToken, Intent intent)
            throws RemoteException {
        boolean doRebind = requireWorker().unbindGuestService(intent);
        listener.onServiceUnbound(token, bindToken, intent, doRebind, userId);
    }

    @Override
    public void scheduleServiceArgs(IBinder token, boolean taskRemoved, int startId,
            int flags, Intent intent) throws RemoteException {
        requireWorker().startGuestService(intent, flags, startId);
    }

    @Override
    public void scheduleStopService(IBinder token) throws RemoteException {
        requireWorker().destroyGuestService();
        listener.onServiceStopped(token, userId);
        close();
    }

    private IIsolatedGuestWorker requireWorker() throws RemoteException {
        synchronized (lock) {
            if (closed || worker == null || !worker.asBinder().isBinderAlive()) {
                throw new RemoteException("isolated worker is unavailable for slot " + slot);
            }
            return worker;
        }
    }

    private void signalCreateFailed(String detail) {
        synchronized (lock) {
            if (terminalCallbackSent) return;
            terminalCallbackSent = true;
        }
        listener.onCreateFailed(this, detail);
    }

    private void signalWorkerDied() {
        synchronized (lock) {
            if (closed || terminalCallbackSent) return;
            terminalCallbackSent = true;
            worker = null;
        }
        listener.onWorkerDied(this);
    }

    @Override public IBinder getToken() { return serviceToken; }
    @Override public IBinder getAppThread() { return asBinder(); }
    @Override public String getDebugInfo() { return "isolated-worker-slot=" + slot; }
    @Override public void scheduleReceiver(String processName, ComponentName component,
            Intent intent, PendingResultData resultData) {}
    @Override public void scheduleNewIntent(String creator, IBinder token, Intent intent) {}
    @Override public void finishActivity(IBinder token) {}
    @Override public IBinder createProxyService(ComponentName component, IBinder binder) {
        return binder;
    }
    @Override public IBinder acquireProviderClient(ProviderInfo info) { return null; }
}
