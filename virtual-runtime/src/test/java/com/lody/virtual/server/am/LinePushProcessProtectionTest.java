package com.lody.virtual.server.am;

import android.content.Context;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LinePushProcessProtectionTest {
    @Test
    public void frozenProcessWaitsForBindingBeforeDispatch() {
        LinePushProcessProtection protection = new LinePushProcessProtection();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();

        assertEquals(LinePushProcessProtection.EnqueueAction.START_BIND,
                protection.enqueue("first", dispatches::incrementAndGet, aborts::incrementAndGet));
        assertEquals(0, dispatches.get());

        run(protection.onConnected());

        assertTrue(protection.isRetained());
        assertEquals(1, dispatches.get());
        assertEquals(0, aborts.get());
    }

    @Test
    public void duplicatePushesShareBindingAndReleaseAfterBothFinish() {
        LinePushProcessProtection protection = new LinePushProcessProtection();
        AtomicInteger dispatches = new AtomicInteger();

        assertEquals(LinePushProcessProtection.EnqueueAction.START_BIND,
                protection.enqueue("first", dispatches::incrementAndGet, () -> {}));
        assertEquals(LinePushProcessProtection.EnqueueAction.WAIT_FOR_BIND,
                protection.enqueue("second", dispatches::incrementAndGet, () -> {}));

        run(protection.onConnected());

        assertEquals(2, dispatches.get());
        assertFalse(protection.complete("first"));
        assertTrue(protection.complete("second"));
        assertTrue(protection.releaseIfIdle());
        assertFalse(protection.isRetained());
    }

    @Test
    public void retainedProcessDispatchesNextPushImmediately() {
        LinePushProcessProtection protection = new LinePushProcessProtection();
        AtomicInteger dispatches = new AtomicInteger();

        protection.enqueue("first", dispatches::incrementAndGet, () -> {});
        run(protection.onConnected());

        assertEquals(LinePushProcessProtection.EnqueueAction.DISPATCH_NOW,
                protection.enqueue("second", dispatches::incrementAndGet, () -> {}));
        dispatches.incrementAndGet();

        assertEquals(2, dispatches.get());
        assertEquals(2, protection.dispatchCount());
    }

    @Test
    public void bindFailureOrOwnerDeathAbortsOnlyUndeliveredPushes() {
        LinePushProcessProtection binding = new LinePushProcessProtection();
        AtomicInteger bindingAborts = new AtomicInteger();
        binding.enqueue("pending", () -> {}, bindingAborts::incrementAndGet);

        run(binding.terminate());

        assertEquals(1, bindingAborts.get());

        LinePushProcessProtection retained = new LinePushProcessProtection();
        AtomicInteger retainedAborts = new AtomicInteger();
        retained.enqueue("active", () -> {}, retainedAborts::incrementAndGet);
        run(retained.onConnected());

        run(retained.terminate());

        assertEquals(0, retainedAborts.get());
    }

    @Test
    public void bindingUsesAutoCreateAndImportantPriority() {
        int flags = LinePushProcessGuard.bindingFlags();

        assertTrue((flags & Context.BIND_AUTO_CREATE) != 0);
        assertTrue((flags & Context.BIND_IMPORTANT) != 0);
    }

    private static void run(List<Runnable> actions) {
        for (Runnable action : actions) {
            action.run();
        }
    }
}
