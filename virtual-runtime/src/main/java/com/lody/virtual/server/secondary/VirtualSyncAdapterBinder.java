package com.lody.virtual.server.secondary;

import android.os.*;
import android.os.Process;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Only the virtual manager may enter the framework SyncAdapter's local system-call boundary. */
public final class VirtualSyncAdapterBinder extends Binder {
    private final Binder base;
    private final int managerPid;
    private final Method dispatch;
    private final IBinder managerAuthority;
    private volatile boolean managerAlive = true;
    public VirtualSyncAdapterBinder(Binder base, int managerPid) {
        this(base, managerPid, new Binder());
    }
    public VirtualSyncAdapterBinder(Binder base, int managerPid, IBinder managerAuthority) {
        this.base = base; this.managerPid = managerPid;
        this.managerAuthority = managerAuthority;
        try { managerAuthority.linkToDeath(() -> managerAlive = false, 0); }
        catch (RemoteException dead) { managerAlive = false; }
        try {
            dispatch = Binder.class.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class);
            dispatch.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Local sync dispatch unavailable"); }
    }
    @Override public String getInterfaceDescriptor() { return "android.content.ISyncAdapter"; }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(getInterfaceDescriptor()); return true; }
        if (!managerAlive || !managerAuthority.isBinderAlive()
                || !VirtualSyncBridgePolicy.accepts(Binder.getCallingPid(), managerPid, base.getInterfaceDescriptor(), code, Build.VERSION.SDK_INT))
            throw new SecurityException("Virtual sync manager authority required");
        long identity = Binder.clearCallingIdentity();
        try {
            // Local framework dispatch only. Kernel Binder calls still originate from our real UID.
            Binder.restoreCallingIdentity(FakeIdentityBinder.composeIdentity(Process.SYSTEM_UID, managerPid));
            boolean accepted = (Boolean) dispatch.invoke(base, code, data, reply, flags);
            if (accepted && reply != null) reply.writeNoException();
            return accepted;
        } catch (IllegalAccessException failure) { throw new RemoteException("Local sync dispatch unavailable"); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RemoteException) throw (RemoteException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RemoteException("Local sync dispatch failed");
        } finally { Binder.restoreCallingIdentity(identity); }
    }
}
