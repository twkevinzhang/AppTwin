package mirror.android.net.nsd;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

/** Reflection bridge for the hidden network service discovery Binder. */
public class INsdManager {
    public static Class<?> TYPE = RefClass.load(INsdManager.class, "android.net.nsd.INsdManager");

    public static class Stub {
        public static Class<?> TYPE = RefClass.load(Stub.class, "android.net.nsd.INsdManager$Stub");

        @MethodParams({IBinder.class})
        public static RefStaticMethod<IInterface> asInterface;
    }
}
