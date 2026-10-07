package com.lody.virtual.client.hook.proxies.appops;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpaceAppOpPermissionTest {
    @Test public void mapsProtectedAccessAndLeavesUnrelatedOpsAlone() {
        assertEquals("android.permission.READ_CONTACTS", SpaceAppOpPermission.permissionFor(4));
        assertEquals("android.permission.WRITE_CONTACTS", SpaceAppOpPermission.permissionFor(5));
        assertEquals("android.permission.CAMERA", SpaceAppOpPermission.permissionFor(26));
        assertEquals("android.permission.RECORD_AUDIO", SpaceAppOpPermission.permissionFor(27));
        assertEquals("android.permission.READ_MEDIA_IMAGES", SpaceAppOpPermission.permissionFor(85));
        assertEquals("android.permission.READ_MEDIA_VIDEO", SpaceAppOpPermission.permissionFor(83));
        assertNull(SpaceAppOpPermission.permissionFor(-1));
        assertNull(SpaceAppOpPermission.permissionFor(10000));
    }
}
