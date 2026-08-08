package com.lody.virtual.server.secondary;

import android.os.Binder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import com.lody.virtual.client.core.VirtualCore;

/**
 * @author Lody
 */
public class FakeIdentityBinder extends Binder {

    private Binder mBase;

    public FakeIdentityBinder(Binder binder) {
        this.mBase = binder;
    }

    public final void attachInterface(IInterface owner, String descriptor) {
        mBase.attachInterface(owner, descriptor);
    }

    public final String getInterfaceDescriptor() {
        return mBase.getInterfaceDescriptor();
    }

    public final boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        long clearCallingIdentity = Binder.clearCallingIdentity();
        try {
            Binder.restoreCallingIdentity(getFakeIdentity());
            return mBase.transact(code, data, reply, flags);
        } finally {
            Binder.restoreCallingIdentity(clearCallingIdentity);
        }
    }

    /**
     * See: http://androidxref.com/6.0.1_r10/xref/frameworks/native/libs/binder/IPCThreadState.cpp#356
     */
    protected long getFakeIdentity() {
        return composeIdentity(getFakeUid(), getFakePid());
    }

    protected int getFakeUid() {
        // Process.myUid() is guest-facing after libcore hooks are installed. VirtualCore caches the
        // kernel-assigned host UID before those hooks, which is the identity Binder must restore.
        return VirtualCore.get().myUid();
    }

    protected int getFakePid() {
        return Process.myPid();
    }

    static long composeIdentity(int hostUid, int hostPid) {
        return (long) hostUid << 32 | (hostPid & 0xffffffffL);
    }

    public final IInterface queryLocalInterface(String descriptor) {
        return mBase.queryLocalInterface(descriptor);
    }
}
