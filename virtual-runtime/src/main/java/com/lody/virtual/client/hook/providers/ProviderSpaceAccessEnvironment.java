package com.lody.virtual.client.hook.providers;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.GuestPermissionPolicy;
import com.lody.virtual.client.hook.GuestSelectedUriGrants;
import com.lody.virtual.helper.compat.FilePickerDiagnostics;

/** Fresh capability reads; implementations must not cache permission decisions. */
interface ProviderSpaceAccessEnvironment {
    boolean isUri(Object value);
    String authority(Object uri);
    String path(Object uri);
    int sdk();
    void require(String permission);
    boolean isGranted(String permission);
    boolean canReadMedia(boolean images, boolean videos);
    boolean hasSelectedRead(Object uri);
    boolean hasHostRead(Object uri);
    boolean hasHostWrite(Object uri);
    void documentDenied(String method, boolean hasUri);
}

final class AndroidProviderSpaceAccessEnvironment implements ProviderSpaceAccessEnvironment {
    @Override public boolean isUri(Object value) { return value instanceof Uri; }
    @Override public String authority(Object uri) { return ((Uri) uri).getAuthority(); }
    @Override public String path(Object uri) { return ((Uri) uri).getPath(); }
    @Override public int sdk() { return Build.VERSION.SDK_INT; }
    @Override public void require(String permission) { GuestPermissionPolicy.require(permission); }
    @Override public boolean isGranted(String permission) { return GuestPermissionPolicy.isGranted(permission); }
    @Override public boolean canReadMedia(boolean images, boolean videos) {
        return GuestPermissionPolicy.canReadMedia(images, videos);
    }
    @Override public boolean hasSelectedRead(Object uri) { return GuestSelectedUriGrants.hasRead((Uri) uri); }
    @Override public boolean hasHostRead(Object uri) { return hasHostGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
    @Override public boolean hasHostWrite(Object uri) { return hasHostGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
    private boolean hasHostGrant(Object uri, int flag) {
        return VirtualCore.get().getContext().checkUriPermission((Uri) uri,
                Process.myPid(), Process.myUid(), flag) == PackageManager.PERMISSION_GRANTED;
    }
    @Override public void documentDenied(String method, boolean hasUri) {
        FilePickerDiagnostics.denied(method, hasUri);
    }
}
