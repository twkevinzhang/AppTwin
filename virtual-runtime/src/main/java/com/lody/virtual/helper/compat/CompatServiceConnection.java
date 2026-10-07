package com.lody.virtual.helper.compat;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Own transport rather than the boot-class IServiceConnection.Stub's changing abstract API. */
public abstract class CompatServiceConnection implements IServiceConnection {
    private static final String DESCRIPTOR = "android.app.IServiceConnection";
    private final Binder transport = new Binder() {
        { attachInterface(CompatServiceConnection.this, DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            ComponentName name = data.readInt() != 0 ? ComponentName.CREATOR.createFromParcel(data) : null;
            IBinder service = data.readStrongBinder();
            if (ServiceConnectionCompat.usesBinderSession(android.os.Build.VERSION.SDK_INT)) data.readStrongBinder();
            boolean dead = data.dataAvail() > 0 && data.readInt() != 0;
            onConnected(name, service, dead);
            return true;
        }
    };

    @Override public final IBinder asBinder() { return transport; }
    @Override public final void connected(ComponentName name, IBinder service) throws RemoteException {
        onConnected(name, service, false);
    }
    // Android 8+ boot interface has this signature even when our bundled AIDL exposes only two args.
    public final void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        onConnected(name, service, dead);
    }
    public abstract void onConnected(ComponentName name, IBinder service, boolean dead) throws RemoteException;
}
