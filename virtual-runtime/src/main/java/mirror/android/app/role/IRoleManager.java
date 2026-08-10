package mirror.android.app.role;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

/** Reflection bridge for the Android 10+ role manager Binder. */
public class IRoleManager {
    public static class Stub {
        public static Class<?> TYPE = RefClass.load(
                Stub.class, "android.app.role.IRoleManager$Stub");

        @MethodParams({IBinder.class})
        public static RefStaticMethod<IInterface> asInterface;
    }
}
