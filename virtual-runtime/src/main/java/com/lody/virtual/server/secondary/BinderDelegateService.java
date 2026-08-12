package com.lody.virtual.server.secondary;

import android.content.ComponentName;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import com.lody.virtual.server.IBinderDelegateService;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Lody
 */

public class BinderDelegateService extends IBinderDelegateService.Stub {

    private ComponentName name;
    private IBinder service;

    private interface ProxyBinderFactory {
        IBinder create(Binder binder);
    }
    private static final Map<String, ProxyBinderFactory> mFactories = new HashMap<>();
    static {
        mFactories.put(FakeIdentityBinder.ACCOUNT_AUTHENTICATOR_DESCRIPTOR, new ProxyBinderFactory() {
            @Override
            public IBinder create(Binder binder) {
                return new FakeIdentityBinder(binder);
            }
        });
    }

    public BinderDelegateService(ComponentName name, IBinder service) {
        this.name = name;
        this.service = createProxyService(service);
    }

    /** Must run in the guest service process while a local Binder is still available. */
    public static IBinder createProxyService(IBinder service) {
        if (service instanceof Binder) {
            Binder localService = (Binder) service;
            ProxyBinderFactory factory = mFactories.get(localService.getInterfaceDescriptor());
            if (factory != null) {
                service = factory.create(localService);
            }
        }
        return service;
    }

    @Override
    public ComponentName getComponent() throws RemoteException {
        return name;
    }

    @Override
    public IBinder getService() throws RemoteException {
        return service;
    }
}
