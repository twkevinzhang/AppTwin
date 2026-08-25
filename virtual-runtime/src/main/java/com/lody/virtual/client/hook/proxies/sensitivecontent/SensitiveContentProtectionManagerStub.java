package com.lody.virtual.client.hook.proxies.sensitivecontent;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import java.lang.reflect.Method;

/** Maps a guest window's package identity to the physical host UID at the system boundary. */
public final class SensitiveContentProtectionManagerStub extends BinderInvocationProxy {

    private static final String SERVICE_NAME = "sensitive_content_protection_service";

    public SensitiveContentProtectionManagerStub() {
        super(loadStubClass(), SERVICE_NAME);
    }

    SensitiveContentProtectionManagerStub(BinderInvocationStub invocationStub) {
        super(invocationStub, SERVICE_NAME);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new SetSensitiveContentProtection());
    }

    private static final class SetSensitiveContentProtection extends MethodProxy {
        @Override
        public String getMethodName() {
            return "setSensitiveContentProtection";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            rewriteCallingPackage(args, getAppPkg(), getHostPkg());
            return true;
        }
    }

    static boolean rewriteCallingPackage(Object[] args, String guestPackage, String hostPackage) {
        if (args == null || args.length != 3 || guestPackage == null || hostPackage == null
                || !(args[1] instanceof String) || !(args[2] instanceof Boolean)
                || !guestPackage.equals(args[1])) {
            return false;
        }
        args[1] = hostPackage;
        return true;
    }

    private static Class<?> loadStubClass() {
        try {
            return Class.forName("android.view.ISensitiveContentProtectionManager$Stub");
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Sensitive content binder is unavailable", error);
        }
    }
}
