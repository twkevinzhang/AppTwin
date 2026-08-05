package com.lody.virtual.client.hook.secondary;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.compat.ServiceConnectionCompat;
import com.lody.virtual.server.IBinderDelegateService;

import mirror.android.app.ActivityThread;
import mirror.android.app.ContextImpl;
import mirror.android.app.LoadedApk;

/**
 * @author Lody
 */

public class ServiceConnectionDelegate implements IServiceConnection {
    private static final String DESCRIPTOR = "android.app.IServiceConnection";
    private final static ArrayMap<IBinder, ServiceConnectionDelegate> DELEGATE_MAP = new ArrayMap<>();
    private IServiceConnection mConn;
    private final IBinder mBinder = new BinderTransport();

    private final class BinderTransport extends android.os.Binder {
        BinderTransport() {
            attachInterface(ServiceConnectionDelegate.this, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code != FIRST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(DESCRIPTOR);
            ComponentName name = data.readInt() != 0
                    ? ComponentName.CREATOR.createFromParcel(data) : null;
            IBinder service = data.readStrongBinder();
            if (ServiceConnectionCompat.usesBinderSession(android.os.Build.VERSION.SDK_INT)) {
                data.readStrongBinder();
            }
            boolean dead = data.readInt() != 0;
            connected(name, service, dead);
            return true;
        }
    }

    private ServiceConnectionDelegate(IServiceConnection mConn) {
        this.mConn = mConn;
    }

    @Override
    public IBinder asBinder() {
        return mBinder;
    }

    public static IServiceConnection getDelegate(Context context, ServiceConnection connection,int flags) {
        IServiceConnection sd = null;
        if (connection == null) {
            throw new IllegalArgumentException("connection is null");
        }
        try {
            Object activityThread = ActivityThread.currentActivityThread.call();
            Object loadApk = ContextImpl.mPackageInfo.get(VirtualCore.get().getContext());
            Handler handler = ActivityThread.getHandler.call(activityThread);
            sd = LoadedApk.getServiceDispatcher.call(loadApk, connection, context, handler, flags);
        } catch (Exception e) {
            Log.e("ConnectionDelegate", "getServiceDispatcher", e);
        }
        if (sd == null) {
            throw new RuntimeException("Not supported in system context");
        }
        return getDelegate(sd);
    }

    public static IServiceConnection removeDelegate(Context context, ServiceConnection conn) {
        IServiceConnection connection = null;
        try{
            Object loadApk = ContextImpl.mPackageInfo.get(VirtualCore.get().getContext());
            connection = LoadedApk.forgetServiceDispatcher.call(loadApk, context, conn);
        }catch (Exception e){
            Log.e("ConnectionDelegate", "forgetServiceDispatcher", e);
        }
        if(connection == null){
            return null;
        }
        return ServiceConnectionDelegate.removeDelegate(connection);
    }

    public static ServiceConnectionDelegate getDelegate(IServiceConnection conn) {
        if(conn instanceof ServiceConnectionDelegate){
            return (ServiceConnectionDelegate)conn;
        }
        IBinder binder = conn.asBinder();
        ServiceConnectionDelegate delegate = DELEGATE_MAP.get(binder);
        if (delegate == null) {
            delegate = new ServiceConnectionDelegate(conn);
            DELEGATE_MAP.put(binder, delegate);
        }
        return delegate;
    }

    public static ServiceConnectionDelegate removeDelegate(IServiceConnection conn) {
        return DELEGATE_MAP.remove(conn.asBinder());
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        IBinderDelegateService delegateService = IBinderDelegateService.Stub.asInterface(service);
        if (delegateService != null) {
            name = delegateService.getComponent();
            service = delegateService.getService();
            IBinder proxy = ProxyServiceFactory.getProxyService(VClientImpl.get().getCurrentApplication(), name, service);
            if (proxy != null) {
                service = proxy;
            }
        }

        ServiceConnectionCompat.connected(mConn, name, service, dead);
    }
}
