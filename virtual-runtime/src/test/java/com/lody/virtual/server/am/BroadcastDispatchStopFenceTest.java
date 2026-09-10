package com.lody.virtual.server.am;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BroadcastDispatchStopFenceTest {
    @Test
    public void packageStopInvalidatesQueuedPermitWithoutBlockingOtherPackage() {
        BroadcastDispatchStopFence fence = new BroadcastDispatchStopFence();
        BroadcastDispatchStopFence.Permit old = fence.acquire("app.one", 4);
        BroadcastDispatchStopFence.Permit other = fence.acquire("app.two", 4);

        BroadcastDispatchStopFence.StopScope stop = fence.beginPackage("app.one", 4);

        assertFalse(fence.isCurrent(old));
        assertTrue(fence.isCurrent(other));
        assertNull(fence.acquire("app.one", 4));
        fence.end(stop);
        assertFalse(fence.isCurrent(old));
        assertNotNull(fence.acquire("app.one", 4));
    }

    @Test
    public void allUsersPackageStopInvalidatesEveryMatchingUser() {
        BroadcastDispatchStopFence fence = new BroadcastDispatchStopFence();
        BroadcastDispatchStopFence.Permit first = fence.acquire("app.one", 1);
        BroadcastDispatchStopFence.Permit second = fence.acquire("app.one", 2);

        BroadcastDispatchStopFence.StopScope stop = fence.beginPackage(
                "app.one", BroadcastDispatchStopFence.ALL_USERS);

        assertFalse(fence.isCurrent(first));
        assertFalse(fence.isCurrent(second));
        assertNull(fence.acquire("app.one", 1));
        fence.end(stop);
        assertNotNull(fence.acquire("app.one", 2));
    }

    @Test
    public void userAndGlobalStopsFenceEveryQueuedGenerationInScope() {
        BroadcastDispatchStopFence fence = new BroadcastDispatchStopFence();
        BroadcastDispatchStopFence.Permit userOne = fence.acquire("app.one", 7);
        BroadcastDispatchStopFence.Permit userTwo = fence.acquire("app.one", 8);

        BroadcastDispatchStopFence.StopScope userStop = fence.beginUser(7);
        assertFalse(fence.isCurrent(userOne));
        assertTrue(fence.isCurrent(userTwo));
        fence.end(userStop);
        assertFalse(fence.isCurrent(userOne));

        BroadcastDispatchStopFence.Permit beforeGlobal = fence.acquire("app.two", 8);
        BroadcastDispatchStopFence.StopScope globalStop = fence.beginAll();
        assertFalse(fence.isCurrent(beforeGlobal));
        assertNull(fence.acquire("app.two", 8));
        fence.end(globalStop);
        assertNotNull(fence.acquire("app.two", 8));
    }

    @Test
    public void nestedStopsRemainClosedUntilLastScopeEnds() {
        BroadcastDispatchStopFence fence = new BroadcastDispatchStopFence();
        BroadcastDispatchStopFence.StopScope first = fence.beginUser(3);
        BroadcastDispatchStopFence.StopScope second = fence.beginUser(3);

        fence.end(first);
        assertNull(fence.acquire("app.one", 3));
        fence.end(second);
        assertNotNull(fence.acquire("app.one", 3));
    }
}
