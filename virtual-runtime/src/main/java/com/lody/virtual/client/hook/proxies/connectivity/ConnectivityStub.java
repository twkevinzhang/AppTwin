package com.lody.virtual.client.hook.proxies.connectivity;

import android.content.Context;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;

import java.lang.reflect.Method;

import mirror.android.net.IConnectivityManager;

/**
 * @author legency
 */
public class ConnectivityStub extends BinderInvocationProxy {

    public ConnectivityStub() {
        super(IConnectivityManager.Stub.asInterface, Context.CONNECTIVITY_SERVICE);
    }

    ConnectivityStub(BinderInvocationStub invocationStub) {
        super(invocationStub, Context.CONNECTIVITY_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addCallingPackageProxy("getDefaultNetworkCapabilitiesForUser");
        addCallingPackageProxy("getNetworkCapabilities");
        addCallingPackageProxy("requestNetwork");
        addCallingPackageProxy("pendingRequestForNetwork");
        addCallingPackageProxy("listenForNetwork");
        addCallingPackageProxy("pendingListenForNetwork");
    }

    private void addCallingPackageProxy(String methodName) {
        addMethodProxy(new ReplaceCallingPackageMethodProxy(methodName));
    }

    static final class ReplaceCallingPackageMethodProxy extends StaticMethodProxy {

        ReplaceCallingPackageMethodProxy(String name) {
            super(name);
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            replaceGuestPackage(args, getAppPkg(), getHostPkg());
            return super.beforeCall(who, method, args);
        }

        static int replaceGuestPackage(Object[] args, String guestPackage, String hostPackage) {
            if (args == null || guestPackage == null || hostPackage == null) {
                return 0;
            }
            int replaced = 0;
            for (int index = 0; index < args.length; index++) {
                if (guestPackage.equals(args[index])) {
                    args[index] = hostPackage;
                    replaced++;
                }
            }
            return replaced;
        }
    }
}
