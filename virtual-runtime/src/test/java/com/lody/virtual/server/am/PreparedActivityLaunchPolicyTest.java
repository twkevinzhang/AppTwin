package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Build;

import com.lody.virtual.os.VUserHandle;

import org.junit.Test;

public class PreparedActivityLaunchPolicyTest {
    private static final String PACKAGE = "com.example.guest";

    @Test
    public void requestAndResolutionMustExactlyMatchExpectedPackage() {
        assertTrue(VActivityManagerService.isPreparedLaunchIntentScopedToPackage(
                PACKAGE, PACKAGE, null));
        assertFalse(VActivityManagerService.isPreparedLaunchIntentScopedToPackage(
                PACKAGE, "com.attacker", PACKAGE));
        assertTrue(VActivityManagerService.isPreparedLaunchResolutionValid(
                PACKAGE, PACKAGE, "com.example.guest.Main", PACKAGE,
                "com.example.guest.Main"));
        assertFalse(VActivityManagerService.isPreparedLaunchResolutionValid(
                PACKAGE, PACKAGE, "com.example.guest.Main", PACKAGE,
                "com.example.guest.Other"));
    }

    @Test
    public void reusedTaskMustBelongToExactRequestedVirtualUser() {
        assertTrue(ActivityStack.canPrepareReusedTask(
                42, 7, 7, true, true, false, false));
        assertFalse(ActivityStack.canPrepareReusedTask(
                42, 7, 8, true, true, false, false));
        assertFalse(ActivityStack.canPrepareReusedTask(
                42, 7, 7, false, true, false, false));
        assertFalse(ActivityStack.canPrepareReusedTask(
                42, 7, 7, true, true, true, false));
        assertTrue(ActivityStack.canPrepareReusedTask(
                42, 7, 7, true, true, true, true));
    }

    @Test
    public void launcherReactivationMayReuseOnlyTheExactCurrentTopActivity() {
        assertTrue(ActivityStack.canReactivateLauncherTask(true, true, true));
        assertFalse(ActivityStack.canReactivateLauncherTask(true, true, false));
        assertFalse(ActivityStack.canReactivateLauncherTask(false, true, true));
        assertFalse(ActivityStack.canReactivateLauncherTask(true, false, true));
    }

    @Test
    public void physicalTaskAttachmentRejectsDifferentVirtualUser() {
        assertTrue(ActivityStack.canAttachActivityToTask(7, VUserHandle.USER_NULL));
        assertTrue(ActivityStack.canAttachActivityToTask(7, 7));
        assertFalse(ActivityStack.canAttachActivityToTask(8, 7));
    }

    @Test
    public void hostStartFlagsAreAppliedWithoutChangingGuestIntent() {
        int guestFlags = Intent.FLAG_ACTIVITY_NO_ANIMATION;
        int hostFlags = ActivityStack.preparedHostActivityFlags(
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                Build.VERSION_CODES.VANILLA_ICE_CREAM);

        assertTrue((hostFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue((hostFlags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0);
        assertTrue((hostFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0);
        assertTrue((guestFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0);
    }
}
