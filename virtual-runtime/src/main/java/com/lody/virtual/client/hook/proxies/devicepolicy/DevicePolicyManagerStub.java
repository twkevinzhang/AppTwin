package com.lody.virtual.client.hook.proxies.devicepolicy;

import android.content.Context;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;

import java.lang.reflect.Method;

import mirror.android.app.admin.IDevicePolicyManager;

/**
 * Created by wy on 2017/10/20.
 */

public class DevicePolicyManagerStub extends BinderInvocationProxy{
    public DevicePolicyManagerStub() {
        super(IDevicePolicyManager.Stub.asInterface, Context.DEVICE_POLICY_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new GetStorageEncryptionStatus());
        // MaskAccounts deliberately exposes no Android device-owner/work-profile state to a
        // guest. The real service rejects these cross-identity queries from the host UID.
        addMethodProxy(new ResultStaticMethodProxy("getDeviceOwnerComponent", null));
        addMethodProxy(new ResultStaticMethodProxy("getProfileOwner", null));
        addMethodProxy(new ResultStaticMethodProxy("getProfileOwnerAsUser", null));
        addMethodProxy(new ResultStaticMethodProxy("getProfileOwnerName", null));
        addMethodProxy(new ResultStaticMethodProxy("getDeviceOwnerName", null));
        addMethodProxy(new ResultStaticMethodProxy("getDeviceOwnerOrganizationName", null));
        addMethodProxy(new ResultStaticMethodProxy("getOrganizationNameForUser", null));
        addMethodProxy(new ResultStaticMethodProxy("getDeviceOwnerUserId", -10000));
        addMethodProxy(new ResultStaticMethodProxy("isDeviceManaged", false));
        addMethodProxy(new ResultStaticMethodProxy(
                "isOrganizationOwnedDeviceWithManagedProfile", false));
        addMethodProxy(new ResultStaticMethodProxy("isProfileOwnerApp", false));
        addMethodProxy(new ResultStaticMethodProxy("isDeviceOwnerApp", false));
    }

    private static class GetStorageEncryptionStatus extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getStorageEncryptionStatus";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            args[0] = VirtualCore.get().getHostPkg();
            return method.invoke(who, args);
        }
    }
}
