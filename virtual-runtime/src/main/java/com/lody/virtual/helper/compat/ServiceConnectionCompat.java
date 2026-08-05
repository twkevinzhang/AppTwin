package com.lody.virtual.helper.compat;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Dispatches IServiceConnection callbacks without relying on hidden method reflection. */
public final class ServiceConnectionCompat {
    private static final String DESCRIPTOR = "android.app.IServiceConnection";

    private ServiceConnectionCompat() {
    }

    public static void connected(IServiceConnection connection, ComponentName component,
                                 IBinder service, boolean dead) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (component == null) {
                data.writeInt(0);
            } else {
                data.writeInt(1);
                component.writeToParcel(data, 0);
            }
            data.writeStrongBinder(service);
            if (usesBinderSession(android.os.Build.VERSION.SDK_INT)) {
                // Android 17 inserts an IBinderSession between the service and dead fields.
                data.writeStrongBinder(null);
            }
            data.writeInt(dead ? 1 : 0);
            if (!connection.asBinder().transact(
                    IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)) {
                throw new RemoteException("IServiceConnection.connected transaction rejected");
            }
        } finally {
            data.recycle();
        }
    }

    public static boolean usesBinderSession(int sdkInt) {
        return sdkInt >= 37;
    }
}
