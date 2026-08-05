package com.lody.virtual.client.hook.proxies.safetycenter;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;

/** Keeps guest apps from querying the host Android user's privileged Safety Center state. */
public class SafetyCenterManagerStub extends BinderInvocationProxy {

    private static final String SERVICE_NAME = "safety_center";

    public SafetyCenterManagerStub() {
        super(loadStubClass(), SERVICE_NAME);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ResultStaticMethodProxy("isSafetyCenterEnabled", false));
    }

    private static Class<?> loadStubClass() {
        try {
            return Class.forName("android.safetycenter.ISafetyCenterManager$Stub");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Safety Center binder is unavailable", e);
        }
    }
}
