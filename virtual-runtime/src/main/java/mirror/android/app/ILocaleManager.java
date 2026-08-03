package mirror.android.app;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

/** Hidden Binder interface backing Android's per-app locale service. */
public class ILocaleManager {
    public static class Stub {
        public static Class<?> TYPE = RefClass.load(ILocaleManager.Stub.class,
                "android.app.ILocaleManager$Stub");

        @MethodParams({IBinder.class})
        public static RefStaticMethod<IInterface> asInterface;
    }
}
