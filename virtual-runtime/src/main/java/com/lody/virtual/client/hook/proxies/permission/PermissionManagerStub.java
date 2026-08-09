package com.lody.virtual.client.hook.proxies.permission;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;

import mirror.android.permission.IPermissionManager;

/**
 * Keeps guest permission observation inside the virtual runtime on Android 11+.
 *
 * <p>The platform moved permission-change listeners from the package binder to the separate
 * {@code permissionmgr} service. A guest cannot register its host UID as a privileged observer,
 * and callers can treat that failure as a service-dispatcher initialization failure. Virtual package
 * permissions are stable for the lifetime of a launched guest, so an inert listener is the
 * correct container-level behavior.</p>
 */
public final class PermissionManagerStub extends BinderInvocationProxy {

    public PermissionManagerStub() {
        super(IPermissionManager.Stub.asInterface, "permissionmgr");
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ResultStaticMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodProxy(new ResultStaticMethodProxy("removeOnPermissionsChangeListener", 0));
    }
}
