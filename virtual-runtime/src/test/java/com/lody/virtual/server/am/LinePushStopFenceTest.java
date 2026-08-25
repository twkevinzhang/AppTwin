package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinePushStopFenceTest {
    private static final String LINE = "jp.naver.line.android";

    @Test
    public void packageUserStopRejectsAdmissionAndPermanentlyInvalidatesOldPermit() {
        LinePushStopFence fence = new LinePushStopFence();
        LinePushStopFence.Permit oldPermit = fence.acquire(LINE, 7);
        assertNotNull(oldPermit);

        LinePushStopFence.StopScope stop = fence.begin(LINE, 7);
        assertNull(fence.acquire(LINE, 7));
        assertFalse(fence.isCurrent(oldPermit));
        assertNotNull(fence.acquire(LINE, 8));

        fence.end(stop);
        assertFalse(fence.isCurrent(oldPermit));
        assertTrue(fence.isCurrent(fence.acquire(LINE, 7)));
    }

    @Test
    public void allUserStopInvalidatesEveryUserButNeverTouchesOtherPackages() {
        LinePushStopFence fence = new LinePushStopFence();
        LinePushStopFence.Permit first = fence.acquire(LINE, 1);
        LinePushStopFence.Permit second = fence.acquire(LINE, 2);

        LinePushStopFence.StopScope stop = fence.begin(LINE, LinePushStopFence.ALL_USERS);
        assertFalse(fence.isCurrent(first));
        assertFalse(fence.isCurrent(second));
        assertNull(fence.acquire(LINE, 1));
        assertNull(fence.acquire("com.example.other", 1));

        fence.end(stop);
        assertTrue(fence.isCurrent(fence.acquire(LINE, 2)));
    }

    @Test
    public void nestedStopsKeepAdmissionClosedUntilLastOwnerEnds() {
        LinePushStopFence fence = new LinePushStopFence();
        LinePushStopFence.StopScope first = fence.begin(LINE, 3);
        LinePushStopFence.StopScope second = fence.begin(LINE, 3);

        fence.end(first);
        assertNull(fence.acquire(LINE, 3));
        fence.end(second);
        assertNotNull(fence.acquire(LINE, 3));
    }
}
