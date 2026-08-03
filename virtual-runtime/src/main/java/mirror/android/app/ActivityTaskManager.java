package mirror.android.app;

import mirror.RefClass;
import mirror.RefStaticObject;

/** Android 10+ cache that backs ActivityTaskManager.getService(). */
public class ActivityTaskManager {
    public static Class<?> TYPE = RefClass.load(
            ActivityTaskManager.class,
            "android.app.ActivityTaskManager");

    public static RefStaticObject<Object> IActivityTaskManagerSingleton;
}
