package com.lody.virtual.client.hook;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuestPermissionPolicyTest {
    @Test public void modernWriteDoesNotRequireAnUnavailableHostLegacyGrant() {
        assertFalse(GuestPermissionPolicy.isControlled("android.permission.WRITE_EXTERNAL_STORAGE", 33));
        assertTrue(GuestPermissionPolicy.isControlled("android.permission.WRITE_EXTERNAL_STORAGE", 32));
    }
    @Test public void writesHaveTheirOwnSpaceControlledCapability() {
        assertTrue(GuestPermissionPolicy.isControlled("android.permission.READ_EXTERNAL_STORAGE"));
        assertTrue(GuestPermissionPolicy.isControlled("android.permission.WRITE_EXTERNAL_STORAGE", 31));
        assertFalse(GuestPermissionPolicy.isControlled("custom.permission.WRITE_EXTERNAL_STORAGE"));
        assertFalse(GuestPermissionPolicy.isControlled("android.permission.WRITE_MEDIA_STORAGE"));
    }
}
