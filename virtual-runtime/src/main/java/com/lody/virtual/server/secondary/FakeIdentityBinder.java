package com.lody.virtual.server.secondary;

import android.os.Binder;
import android.os.Build;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * @author Lody
 */
public class FakeIdentityBinder extends Binder {

    static final String ACCOUNT_AUTHENTICATOR_DESCRIPTOR =
            "android.accounts.IAccountAuthenticator";

    private Binder mBase;
    private final int mAccountManagerPid;
    private final Method mBaseOnTransact;

    public FakeIdentityBinder(Binder binder) {
        this(binder, Binder.getCallingPid());
    }

    FakeIdentityBinder(Binder binder, int accountManagerPid) {
        this.mBase = binder;
        this.mAccountManagerPid = accountManagerPid;
        try {
            mBaseOnTransact = Binder.class.getDeclaredMethod(
                    "onTransact", int.class, Parcel.class, Parcel.class, int.class);
            mBaseOnTransact.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Binder.onTransact is unavailable", e);
        }
        installVirtualAccountPermissionEnforcer();
    }

    public final void attachInterface(IInterface owner, String descriptor) {
        mBase.attachInterface(owner, descriptor);
    }

    public final String getInterfaceDescriptor() {
        return mBase.getInterfaceDescriptor();
    }

    public final boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // Binder.transact() would enter the driver again and replace the virtual AccountManager
        // caller with this guest process. Dispatch locally so the constrained permission enforcer
        // can verify the original caller PID captured when the bridge was created.
        return invokeBaseOnTransact(code, data, reply, flags);
    }

    private void installVirtualAccountPermissionEnforcer() {
        if (Build.VERSION.SDK_INT < 35
                || !ACCOUNT_AUTHENTICATOR_DESCRIPTOR.equals(getInterfaceDescriptor())) {
            return;
        }
        Class<?> type = mBase.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("mEnforcer");
                field.setAccessible(true);
                field.set(mBase, new VirtualAccountPermissionEnforcer(mAccountManagerPid));
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to mediate authenticator permission", e);
            }
        }
        throw new IllegalStateException("Authenticator permission enforcer is unavailable");
    }

    private boolean invokeBaseOnTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return (Boolean) mBaseOnTransact.invoke(mBase, code, data, reply, flags);
        } catch (IllegalAccessException e) {
            throw new RemoteException("Unable to dispatch local Binder transaction: " + e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RemoteException) throw (RemoteException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RemoteException("Local Binder transaction failed: " + cause);
        }
    }

    /**
     * See: http://androidxref.com/6.0.1_r10/xref/frameworks/native/libs/binder/IPCThreadState.cpp#356
     */
    static long composeIdentity(int hostUid, int hostPid) {
        return (long) hostUid << 32 | (hostPid & 0xffffffffL);
    }

    public final IInterface queryLocalInterface(String descriptor) {
        return mBase.queryLocalInterface(descriptor);
    }
}
