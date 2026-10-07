package com.lody.virtual.helper.compat;

import android.accounts.Account;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** AOSP ISyncAdapter wire: API 28 inserted onUnsyncableAccount before startSync. */
public final class SyncAdapterWire {
    private SyncAdapterWire() {}
    public static int startTransaction(int sdk) { return sdk >= 28 ? 2 : 1; }
    public static int cancelTransaction(int sdk) { return sdk >= 28 ? 3 : 2; }
    public static void start(IBinder endpoint, IBinder context, String authority,
                             Account account, Bundle extras, int sdk) throws RemoteException {
        start(endpoint, context, authority, account, extras, sdk, false);
    }
    public static void startManagerBridge(IBinder endpoint, IBinder context, String authority,
                                          Account account, Bundle extras, int sdk) throws RemoteException {
        start(endpoint, context, authority, account, extras, sdk, true);
    }
    private static void start(IBinder endpoint, IBinder context, String authority,
                              Account account, Bundle extras, int sdk, boolean managerBridge) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = managerBridge ? Parcel.obtain() : null;
        try {
            data.writeInterfaceToken("android.content.ISyncAdapter");
            data.writeStrongBinder(context); data.writeString(authority);
            data.writeInt(1); account.writeToParcel(data, 0);
            data.writeInt(1); extras.writeToParcel(data, 0);
            if (!endpoint.transact(startTransaction(sdk), data, reply, managerBridge ? 0 : IBinder.FLAG_ONEWAY))
                throw new RemoteException("Sync adapter rejected start transaction");
            if (reply != null) reply.readException();
        } finally { data.recycle(); if (reply != null) reply.recycle(); }
    }
    public static void cancel(IBinder endpoint, IBinder context, int sdk) throws RemoteException {
        cancel(endpoint, context, sdk, false);
    }
    public static void cancelManagerBridge(IBinder endpoint, IBinder context, int sdk) throws RemoteException {
        cancel(endpoint, context, sdk, true);
    }
    private static void cancel(IBinder endpoint, IBinder context, int sdk, boolean managerBridge) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = managerBridge ? Parcel.obtain() : null;
        try {
            data.writeInterfaceToken("android.content.ISyncAdapter"); data.writeStrongBinder(context);
            if (!endpoint.transact(cancelTransaction(sdk), data, reply, managerBridge ? 0 : IBinder.FLAG_ONEWAY))
                throw new RemoteException("Sync adapter rejected cancellation");
            if (reply != null) reply.readException();
        } finally { data.recycle(); if (reply != null) reply.recycle(); }
    }
}
