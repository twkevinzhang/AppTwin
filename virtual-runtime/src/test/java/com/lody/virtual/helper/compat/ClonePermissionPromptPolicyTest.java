package com.lody.virtual.helper.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ClonePermissionPromptPolicyTest {
    private static final String IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final String VIDEO = "android.permission.READ_MEDIA_VIDEO";
    private static final String READ_STORAGE = "android.permission.READ_EXTERNAL_STORAGE";

    private static ClonePermissionPromptPolicy.HostGrants host(boolean readStorage, boolean allFiles, String... granted) {
        List<String> grants = Arrays.asList(granted);
        return new ClonePermissionPromptPolicy.HostGrants() {
            @Override public boolean granted(String permission) {
                return grants.contains(permission) || (readStorage && READ_STORAGE.equals(permission));
            }
            @Override public boolean allFiles() { return allFiles; }
        };
    }

    @Test public void controlledKeepsOrderDropsDuplicatesAndUncontrolled() {
        List<String> result = ClonePermissionPromptPolicy.controlled(new String[]{
                IMAGES, "android.permission.INTERNET", VIDEO, IMAGES, null});
        assertEquals(Arrays.asList(IMAGES, VIDEO), result);
        assertTrue(ClonePermissionPromptPolicy.controlled(null).isEmpty());
    }

    @Test public void mediaPermissionsCollapseIntoOneLabel() {
        assertEquals(Collections.singletonList("照片與影片"),
                ClonePermissionPromptPolicy.labels(Arrays.asList(IMAGES, VIDEO, READ_STORAGE)));
    }

    @Test public void mediaNeedsHostReadStorageOrAllFiles() {
        assertFalse(ClonePermissionPromptPolicy.hostSatisfied(IMAGES, host(false, false, IMAGES, VIDEO)));
        assertTrue(ClonePermissionPromptPolicy.hostSatisfied(IMAGES, host(true, false)));
        assertTrue(ClonePermissionPromptPolicy.hostSatisfied(VIDEO, host(false, true)));
    }

    @Test public void nonMediaNeedsItsOwnHostGrant() {
        String camera = "android.permission.CAMERA";
        assertFalse(ClonePermissionPromptPolicy.hostSatisfied(camera, host(true, true)));
        assertTrue(ClonePermissionPromptPolicy.hostSatisfied(camera, host(false, false, camera)));
    }

    @Test public void allHostSatisfiedRequiresEveryPermission() {
        String camera = "android.permission.CAMERA";
        assertFalse(ClonePermissionPromptPolicy.allHostSatisfied(Arrays.asList(IMAGES, camera), host(true, false)));
        assertTrue(ClonePermissionPromptPolicy.allHostSatisfied(Arrays.asList(IMAGES, camera), host(true, false, camera)));
    }

    @Test public void specialAccessActionsHaveLabels() {
        assertEquals("裝置管理員", ClonePermissionPromptPolicy.specialAccessLabel("android.app.action.ADD_DEVICE_ADMIN"));
        assertEquals("重疊顯示於其他應用程式上方",
                ClonePermissionPromptPolicy.specialAccessLabel("android.settings.action.MANAGE_OVERLAY_PERMISSION"));
        assertEquals(ClonePermissionPromptPolicy.specialAccessLabel("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"),
                ClonePermissionPromptPolicy.specialAccessLabel("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
    }

    @Test public void unrelatedActionsAreNotSpecialAccess() {
        assertEquals(null, ClonePermissionPromptPolicy.specialAccessLabel("android.settings.WIFI_SETTINGS"));
        assertEquals(null, ClonePermissionPromptPolicy.specialAccessLabel(null));
    }

    @Test public void onlyMediaGrantsMayNeedRestart() {
        assertTrue(ClonePermissionPromptPolicy.mayNeedRestart(Arrays.asList("android.permission.CAMERA", VIDEO)));
        assertFalse(ClonePermissionPromptPolicy.mayNeedRestart(Collections.singletonList("android.permission.CAMERA")));
    }
}
