package com.lody.virtual.client.hook.proxies.role;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;

import mirror.android.app.role.IRoleManager;

/** Keeps Android OS roles outside the guest package namespace. */
public final class RoleManagerStub extends BinderInvocationProxy {
    private static final String SERVICE_NAME = "role";

    public RoleManagerStub() {
        super(IRoleManager.Stub.asInterface, SERVICE_NAME);
    }

    RoleManagerStub(BinderInvocationStub invocationStub) {
        super(invocationStub, SERVICE_NAME);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();

        // A virtual package can never hold a role assigned by the physical Android user. Passing
        // its package name to RoleService would also fail AppOpsManager.checkPackage because the
        // process runs under the host UID.
        addMethodProxy(new ResultStaticMethodProxy("isRoleHeldAsUser", false));
    }
}
