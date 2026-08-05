package com.lody.virtual.client.hook.proxies.appops;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;

import java.lang.reflect.Method;

import mirror.com.android.internal.app.IAppOpsService;

/**
 * @author Lody
 *         <p>
 *         Fuck the AppOpsService.
 * @see android.app.AppOpsManager
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
public class AppOpsManagerStub extends BinderInvocationProxy {

    public AppOpsManagerStub() {
        super(IAppOpsService.Stub.asInterface, Context.APP_OPS_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new BaseMethodProxy("checkOperation", 1, 2));
        addMethodProxy(new BaseMethodProxy("checkOperationForDevice", 1, 2));
        addMethodProxy(new BaseMethodProxy("checkOperationRaw", 1, 2));
        addMethodProxy(new BaseMethodProxy("checkOperationRawForDevice", 1, 2));
        addMethodProxy(new BaseMethodProxy("noteOperation", 1, 2));
        addMethodProxy(new BaseMethodProxy("noteOperationForDevice", 1, 2));
        addMethodProxy(new BaseMethodProxy("startOperation", 2, 3));
        addMethodProxy(new BaseMethodProxy("startOperationForDevice", 2, 3));
        addMethodProxy(new BaseMethodProxy("finishOperation", 2, 3));
        addMethodProxy(new BaseMethodProxy("finishOperationForDevice", 2, 3));
        addMethodProxy(new BaseMethodProxy("isOperationActive", 1, 2));
        addMethodProxy(new BaseMethodProxy("isProxying", 0, 1));
        addMethodProxy(new BaseMethodProxy("startWatchingMode", -1, 1));
        addMethodProxy(new BaseMethodProxy("startWatchingModeWithFlags", -1, 1));
        addMethodProxy(new BaseMethodProxy("checkPackage", 0, 1));
        addMethodProxy(new BaseMethodProxy("getOpsForPackage", 0, 1));
        addMethodProxy(new BaseMethodProxy("setMode", 1, 2));
        addMethodProxy(new BaseMethodProxy("checkAudioOperation", 2, 3));
        addMethodProxy(new BaseMethodProxy("setAudioRestriction", 2, -1));
        addMethodProxy(new ReplaceLastPkgMethodProxy("resetAllModes"));
        addMethodProxy(new MethodProxy() {
            @Override
            public String getMethodName() {
                return "noteProxyOperation";
            }

            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                return 0;
            }
        });
    }

    private class BaseMethodProxy extends StaticMethodProxy {
        final int pkgIndex;
        final int uidIndex;

        BaseMethodProxy(String name, int uidIndex, int pkgIndex) {
            super(name);
            this.pkgIndex = pkgIndex;
            this.uidIndex = uidIndex;
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            AppOpsArgumentRewriter.rewrite(args, uidIndex, pkgIndex,
                    getRealUid(), getHostPkg());
            return true;
        }
    }
}
