package com.lody.virtual.client.hook.proxies.locale;

import android.annotation.TargetApi;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;
import com.lody.virtual.os.VUserHandle;

import java.lang.reflect.Method;

import mirror.android.app.ILocaleManager;

/** Virtualizes the Android 13+ per-app locale Binder service. */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
public class LocaleManagerStub extends BinderInvocationProxy {
    private static final String SERVICE_NAME = "locale";

    public LocaleManagerStub() {
        super(ILocaleManager.Stub.TYPE, SERVICE_NAME);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new GetApplicationLocales());

        // Guest locale changes must not mutate the host package's persisted locale settings.
        addMethodProxy(new NoOpVoidMethod("setApplicationLocales"));
        addMethodProxy(new NoOpVoidMethod("setOverrideLocaleConfig"));

        // An override belonging to the host is not part of the guest's virtual package state.
        addMethodProxy(new ReturnNullMethod("getOverrideLocaleConfig"));
    }

    private static class GetApplicationLocales extends MethodProxy {
        @Override
        public String getMethodName() {
            return "getApplicationLocales";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            rewriteApplicationLocalesArgs(args, getHostPkg(), getRealUid());
            return true;
        }
    }

    private static class NoOpVoidMethod extends StaticMethodProxy {
        NoOpVoidMethod(String name) {
            super(name);
        }

        @Override
        public Object call(Object who, Method method, Object... args) {
            return null;
        }
    }

    private static class ReturnNullMethod extends StaticMethodProxy {
        ReturnNullMethod(String name) {
            super(name);
        }

        @Override
        public Object call(Object who, Method method, Object... args) {
            return null;
        }
    }

    /**
     * Rewrites only the documented ILocaleManager package/user pair. The Android user ID must be
     * derived from the host process UID; the guest's virtual user ID is not an OS user.
     */
    static boolean rewriteApplicationLocalesArgs(Object[] args, String hostPackage, int realUid) {
        if (args == null || args.length < 2 || hostPackage == null
                || !(args[0] instanceof String) || !(args[1] instanceof Integer)) {
            return false;
        }
        args[0] = hostPackage;
        args[1] = VUserHandle.getUserId(realUid);
        return true;
    }
}
