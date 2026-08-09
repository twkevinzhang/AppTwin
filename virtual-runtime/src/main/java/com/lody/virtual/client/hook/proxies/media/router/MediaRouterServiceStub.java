package com.lody.virtual.client.hook.proxies.media.router;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;

import java.lang.reflect.Method;

import mirror.android.media.IMediaRouterService;

/**
 * @author Lody
 * @see android.media.MediaRouter
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class MediaRouterServiceStub extends BinderInvocationProxy {

    private static final int REGISTRATION_PACKAGE_INDEX = 1;

    public MediaRouterServiceStub() {
        super(IMediaRouterService.Stub.asInterface, Context.MEDIA_ROUTER_SERVICE);
    }

    MediaRouterServiceStub(BinderInvocationStub invocationStub) {
        super(invocationStub, Context.MEDIA_ROUTER_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplaceRegistrationPackageMethodProxy("registerClientAsUser"));
        addMethodProxy(new ReplaceRegistrationPackageMethodProxy("registerRouter2"));
        addMethodProxy(new ReplaceRegistrationPackageMethodProxy("registerManager"));
    }

    interface HostPackageProvider {
        String getHostPackage();
    }

    static final class ReplaceRegistrationPackageMethodProxy extends StaticMethodProxy {

        private final HostPackageProvider hostPackageProvider;

        ReplaceRegistrationPackageMethodProxy(String name) {
            this(name, MethodProxy::getHostPkg);
        }

        ReplaceRegistrationPackageMethodProxy(String name,
                HostPackageProvider hostPackageProvider) {
            super(name);
            this.hostPackageProvider = hostPackageProvider;
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            replacePackageArgument(args, REGISTRATION_PACKAGE_INDEX,
                    hostPackageProvider.getHostPackage());
            return super.beforeCall(who, method, args);
        }

        static boolean replacePackageArgument(Object[] args, int packageIndex, String hostPackage) {
            if (args == null
                    || packageIndex < 0
                    || packageIndex >= args.length
                    || !(args[packageIndex] instanceof String)) {
                return false;
            }
            args[packageIndex] = hostPackage;
            return true;
        }
    }
}
