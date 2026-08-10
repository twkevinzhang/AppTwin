package com.lody.virtual.client;

import static org.junit.Assert.assertEquals;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Test;

public class GuestPackageIdentityTest {

    @Test
    public void exposeHostUid_updatesApplicationAndPackageMetadata() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = 10005;
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = applicationInfo;

        GuestPackageIdentity.exposeHostUid(packageInfo, 10311);

        assertEquals(10311, applicationInfo.uid);
    }
}
