package mirror.android.permission;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

/** Reflection bridge for the permissionmgr binder introduced on modern Android. */
public class IPermissionManager {
    public static Class<?> TYPE = RefClass.load(
            IPermissionManager.class, "android.permission.IPermissionManager");

    public static class Stub {
        public static Class<?> TYPE = RefClass.load(
                Stub.class, "android.permission.IPermissionManager$Stub");

        @MethodParams({IBinder.class})
        public static RefStaticMethod<IInterface> asInterface;
    }
}
