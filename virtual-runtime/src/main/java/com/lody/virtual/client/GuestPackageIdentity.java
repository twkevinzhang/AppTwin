package com.lody.virtual.client;

import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;

/** Normalizes guest-visible package metadata to the Linux process that owns the guest. */
public final class GuestPackageIdentity {
    private GuestPackageIdentity() {
    }

    public static ApplicationInfo exposeHostUid(ApplicationInfo info, int hostUid) {
        if (info != null && hostUid >= 0) {
            info.uid = hostUid;
        }
        return info;
    }

    public static PackageInfo exposeHostUid(PackageInfo info, int hostUid) {
        if (info != null && hostUid >= 0) {
            exposeHostUid(info.applicationInfo, hostUid);
        }
        return info;
    }

    public static <T extends ComponentInfo> T exposeHostUid(T info, int hostUid) {
        if (info != null && hostUid >= 0) {
            exposeHostUid(info.applicationInfo, hostUid);
        }
        return info;
    }

    public static ResolveInfo exposeHostUid(ResolveInfo info, int hostUid) {
        if (info != null && hostUid >= 0) {
            exposeHostUid(info.activityInfo, hostUid);
            exposeHostUid(info.serviceInfo, hostUid);
            exposeHostUid(info.providerInfo, hostUid);
        }
        return info;
    }
}
