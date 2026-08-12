package com.lody.virtual.client.hook.proxies.nsd;

import android.content.Context;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.StaticMethodProxy;

import java.lang.reflect.Method;

import mirror.android.net.nsd.INsdManager;

/** Rewrites virtual package identity before connecting to Android's NSD service. */
public class NsdManagerStub extends BinderInvocationProxy {

    public NsdManagerStub() {
        super(INsdManager.Stub.asInterface, Context.NSD_SERVICE);
    }

    NsdManagerStub(BinderInvocationStub invocationStub) {
        super(invocationStub, Context.NSD_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplaceCallingPackageMethodProxy("connect"));
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
