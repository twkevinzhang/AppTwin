package com.lody.virtual.client.hook.proxies.permission;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.GuestPermissionPolicy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;
import com.lody.virtual.helper.utils.Reflect;

import java.lang.reflect.Method;

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
        addMethodProxy(new GetPermissionRequestState());
    }

    /**
     * Activity.requestPermissions asks the platform whether a permission is still requestable and
     * skips the request entirely when the host UID already holds it. A space grant is a separate
     * decision, so a permission AppTwin gates per space stays requestable until that space grants
     * it; otherwise the request never reaches the AppTwin prompt.
     */
    static final class GetPermissionRequestState extends MethodProxy {
        // Matches PermissionManager.PERMISSION_REQUEST_STATE_REQUESTABLE if the hidden field is unreadable.
        private static final int REQUESTABLE_FALLBACK = 1;

        @Override
        public String getMethodName() {
            return "getPermissionRequestState";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (args != null && args.length > 1 && args[1] instanceof String) {
                String permission = (String) args[1];
                if (GuestPermissionPolicy.isControlled(permission) && !GuestPermissionPolicy.isGranted(permission)) {
                    return requestable();
                }
            }
            return method.invoke(who, args);
        }

        private static int requestable() {
            try {
                return (Integer) Reflect.on("android.permission.PermissionManager")
                        .get("PERMISSION_REQUEST_STATE_REQUESTABLE");
            } catch (Throwable unavailable) {
                return REQUESTABLE_FALLBACK;
            }
        }
    }
}
