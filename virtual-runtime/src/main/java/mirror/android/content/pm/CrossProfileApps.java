package mirror.android.content.pm;

import android.os.IInterface;

import mirror.RefClass;
import mirror.RefObject;

public final class CrossProfileApps {
    public static final Class<?> TYPE = RefClass.load(
            CrossProfileApps.class, "android.content.pm.CrossProfileApps");

    public static RefObject<IInterface> mService;

    private CrossProfileApps() {
    }
}
