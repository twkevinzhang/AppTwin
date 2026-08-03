package com.lody.virtual.client.hook.proxies.view;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.util.Log;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import mirror.android.view.IAutoFillManager;

/**
 * @author 陈磊.
 */

public class AutoFillManagerStub extends BinderInvocationProxy {

    private static final String TAG = "AutoFillManagerStub";

    private static final String AUTO_FILL_NAME = "autofill";
    public AutoFillManagerStub() {
        super(IAutoFillManager.Stub.asInterface, AUTO_FILL_NAME);
    }

    AutoFillManagerStub(BinderInvocationStub invocationStub) {
        super(invocationStub, AUTO_FILL_NAME);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplacePkgAndComponentProxy("startSession"));
        addMethodProxy(new ReplacePkgAndComponentProxy("updateOrRestartSession"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("isServiceEnabled"));
    }

    @SuppressLint("WrongConstant")
    @Override
    public void inject() throws Throwable {
        super.inject();
        try {
            Object autoFillManagerInstance = getContext().getSystemService(AUTO_FILL_NAME);
            if (autoFillManagerInstance == null) {
                throw new NullPointerException("AutoFillManagerInstance is null.");
            }
            Object autoFillManagerProxy = getInvocationStub().getProxyInterface();
            if (autoFillManagerProxy == null) {
                throw new NullPointerException("AutoFillManagerProxy is null.");
            }
            rebindCachedService(autoFillManagerInstance, autoFillManagerProxy);
        } catch (Throwable tr) {
            // The Binder service replacement above remains active even when Android's cached
            // AutofillManager cannot be created or rebound during early Application startup.
            Log.w(TAG, "Unable to rebind cached AutofillManager; Binder hooks remain active.", tr);
        }
    }

    static void rebindCachedService(Object autoFillManagerInstance, Object autoFillManagerProxy)
            throws ReflectiveOperationException {
        Field autoFillManagerServiceField = autoFillManagerInstance.getClass()
                .getDeclaredField("mService");
        autoFillManagerServiceField.setAccessible(true);
        autoFillManagerServiceField.set(autoFillManagerInstance, autoFillManagerProxy);
    }

    static class ReplacePkgAndComponentProxy extends ReplaceLastPkgMethodProxy {

        ReplacePkgAndComponentProxy(String name) {
            super(name);
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            replaceLastAppComponent(args, getHostPkg());
            return super.beforeCall(who, method, args);
        }

        static ComponentName replaceLastAppComponent(Object[] args, String hostPkg) {
            int index = ArrayUtils.indexOfLast(args, ComponentName.class);
            if (index != -1) {
                ComponentName orig = (ComponentName) args[index];
                ComponentIdentity identity = componentIdentityForHost(hostPkg, orig.getClassName());
                ComponentName newComponent = new ComponentName(identity.packageName,
                        identity.className);
                args[index] = newComponent;
                return newComponent;
            }
            return null;
        }
    }

    static ComponentIdentity componentIdentityForHost(String hostPkg, String originalClassName) {
        return new ComponentIdentity(hostPkg, originalClassName);
    }

    static final class ComponentIdentity {
        final String packageName;
        final String className;

        ComponentIdentity(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }
    }

}
