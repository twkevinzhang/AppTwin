package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PreparedActivityLaunchRegistryTest {
    @Test
    public void exactUserAndEquivalentTokenAcknowledgeWaitingHost() throws Exception {
        PreparedActivityLaunchRegistry registry = new PreparedActivityLaunchRegistry();
        EqualToken registered = new EqualToken("token-1");
        registry.register("launch-1", 7, registered);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            result.set(registry.await("launch-1", 1_000L));
        });

        waiter.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        registry.acknowledge(7, new EqualToken("token-1"));
        waiter.join(1_000L);

        assertFalse(waiter.isAlive());
        assertTrue(result.get());
        assertEquals(0, registry.pendingCount());
    }

    @Test
    public void wrongUserCannotAcknowledgeExactToken() {
        PreparedActivityLaunchRegistry registry = new PreparedActivityLaunchRegistry();
        Object token = new Object();
        registry.register("launch-2", 7, token);

        registry.acknowledge(8, token);

        assertFalse(registry.await("launch-2", 1L));
    }

    @Test
    public void crossUserAttachFailsClosedAndCannotAcknowledge() {
        PreparedActivityLaunchRegistry registry = new PreparedActivityLaunchRegistry();
        Object token = new Object();
        registry.register("launch-3", 7, null);

        assertFalse(registry.attachActivity("launch-3", 8, token));
        registry.acknowledge(8, token);

        assertFalse(registry.await("launch-3", 1L));
        assertEquals(0, registry.pendingCount());
    }

    @Test
    public void timeoutIsBoundedAndCleansEntry() {
        PreparedActivityLaunchRegistry registry = new PreparedActivityLaunchRegistry();
        registry.register("launch-4", 7, null);
        long started = System.nanoTime();

        assertFalse(registry.await("launch-4", 20L));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("wait took " + elapsedMs + "ms", elapsedMs < 1_000L);
        assertEquals(0, registry.pendingCount());
    }

    @Test
    public void rejectionCancelsOnlyLaunchOwnedBySameVirtualUser() {
        PreparedActivityLaunchRegistry registry = new PreparedActivityLaunchRegistry();
        registry.register("launch-5", 7, null);

        assertFalse(registry.cancelForUser("launch-5", 8));
        assertEquals(1, registry.pendingCount());
        assertTrue(registry.cancelForUser("launch-5", 7));
        assertEquals(0, registry.pendingCount());
    }

    private static final class EqualToken {
        private final String value;

        EqualToken(String value) { this.value = value; }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualToken && value.equals(((EqualToken) other).value);
        }

        @Override
        public int hashCode() { return value.hashCode(); }
    }
}
