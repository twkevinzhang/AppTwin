package android.app.servertransaction;

import android.os.IBinder;
import java.util.List;

/** Shape-only fixture for the hidden AOSP transaction item, never packaged in the APK. */
public final class ActivityResultItem {
    public List<?> mResultInfoList;
    private final IBinder mActivityToken;
    public ActivityResultItem(IBinder token, List<?> results) {
        mActivityToken = token;
        mResultInfoList = results;
    }
    public IBinder getActivityToken() { return mActivityToken; }
}
