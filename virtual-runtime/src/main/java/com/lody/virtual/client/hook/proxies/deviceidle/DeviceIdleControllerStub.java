package com.lody.virtual.client.hook.proxies.deviceidle;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;

import mirror.android.os.IDeviceIdleController;

/** Reads the real battery exemption of the process hosting the guest. */
public class DeviceIdleControllerStub extends BinderInvocationProxy {
    public DeviceIdleControllerStub() {
        super(IDeviceIdleController.Stub.asInterface, "deviceidle");
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new SelfBatteryWhitelistQuery("isPowerSaveWhitelistApp"));
        addMethodProxy(new SelfBatteryWhitelistQuery("isPowerSaveWhitelistExceptIdleApp"));
    }
}
