package com.lody.virtual.client.hook;
import android.net.Uri;
import com.lody.virtual.client.ipc.VActivityManager;
/** Selected grants are proven and persisted by the trusted host picker, never guest reports. */
public final class GuestSelectedUriGrants {
    private GuestSelectedUriGrants() {}
    public static boolean hasRead(Uri uri) {
        return uri != null && VActivityManager.get().hasSelectedUriGrant(uri.toString());
    }
}
