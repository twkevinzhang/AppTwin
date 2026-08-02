package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DynamicReceiverPermissionCompatTest {
    @Test
    public void recognizesAndroidXGuestPermission() {
        assertTrue(DynamicReceiverPermissionCompat.isSyntheticPermission(
                "jp.naver.line.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"));
        assertFalse(DynamicReceiverPermissionCompat.isSyntheticPermission(null));
        assertFalse(DynamicReceiverPermissionCompat.isSyntheticPermission(
                "jp.naver.line.android.permission.RECEIVE"));
    }

    @Test
    public void rewritesToHostOwnedSignaturePermission() {
        assertEquals(
                "org.maskaccounts.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                DynamicReceiverPermissionCompat.forHost("org.maskaccounts"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DynamicReceiverPermissionCompat.forHost(""));
    }
}
