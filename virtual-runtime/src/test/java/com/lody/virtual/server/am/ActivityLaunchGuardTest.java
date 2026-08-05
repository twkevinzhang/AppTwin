package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ActivityLaunchGuardTest {

    @Test
    public void prefersLiveSourceFromReuseTask() {
        Object source = new Object();

        assertSame(source, ActivityLaunchGuard.selectLaunchAnchor(
                source, new Object(), new Object()));
    }

    @Test
    public void usesCurrentUnmarkedTopWithoutSourceInReuseTask() {
        Object top = new Object();

        assertSame(top, ActivityLaunchGuard.selectLaunchAnchor(
                null, top, new Object()));
    }

    @Test
    public void staleClearTopFallsBackToPreMarkTopWhenEverythingWasMarked() {
        Object preMarkTop = new Object();

        assertSame(preMarkTop, ActivityLaunchGuard.selectLaunchAnchor(
                null, null, preMarkTop));
    }

    @Test
    public void clearCommitsOnlyAfterDeliveryOrSuccessorStart() {
        assertFalse(ActivityLaunchGuard.shouldCommitClear(true, false, false));
        assertTrue(ActivityLaunchGuard.shouldCommitClear(true, true, false));
        assertTrue(ActivityLaunchGuard.shouldCommitClear(true, false, true));
        assertFalse(ActivityLaunchGuard.shouldCommitClear(false, true, true));
    }
}
