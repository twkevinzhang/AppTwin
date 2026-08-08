package mirror.android.app;

import android.os.IBinder;

import java.util.List;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefMethod;

/** ActivityThread methods whose descriptors changed in Android 12. */
public class ActivityThreadS {
    public static Class<?> TYPE = RefClass.load(ActivityThreadS.class, "android.app.ActivityThread");

    @MethodParams({IBinder.class})
    public static RefMethod<Object> getActivityClient;

    @MethodParams({ActivityThread.ActivityClientRecord.class, List.class})
    public static RefMethod<Void> handleNewIntent;
}
