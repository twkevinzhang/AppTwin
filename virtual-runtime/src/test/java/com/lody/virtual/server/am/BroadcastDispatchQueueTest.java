package com.lody.virtual.server.am;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BroadcastDispatchQueueTest {
    @Test
    public void preservesFifoWithSingleInFlightPerOwner() {
        BroadcastDispatchQueue<String> queue = new BroadcastDispatchQueue<>(3, 6);
        Object owner = new Object();

        assertEquals(BroadcastDispatchQueue.EnqueueResult.ACCEPTED,
                queue.enqueue(owner, 7, 1, "one"));
        assertEquals(BroadcastDispatchQueue.EnqueueResult.ACCEPTED,
                queue.enqueue(owner, 7, 2, "two"));

        BroadcastDispatchQueue.Item<String> first = queue.takeNext(owner, 7);
        assertEquals("one", first.value);
        assertNull(queue.takeNext(owner, 7));
        assertSame(first, queue.complete(owner, 7, 1));
        assertEquals("two", queue.takeNext(owner, 7).value);
    }

    @Test
    public void enforcesPerProcessAndGlobalBoundsIncludingInFlight() {
        BroadcastDispatchQueue<String> queue = new BroadcastDispatchQueue<>(2, 3);
        Object first = new Object();
        Object second = new Object();

        queue.enqueue(first, 1, 1, "a");
        queue.takeNext(first, 1);
        queue.enqueue(first, 1, 2, "b");
        assertEquals(BroadcastDispatchQueue.EnqueueResult.PROCESS_FULL,
                queue.enqueue(first, 1, 3, "c"));
        queue.enqueue(second, 2, 4, "d");
        assertEquals(BroadcastDispatchQueue.EnqueueResult.GLOBAL_FULL,
                queue.enqueue(new Object(), 3, 5, "e"));
        assertEquals(3, queue.size());
    }

    @Test
    public void staleGenerationCannotDispatchOrAcknowledgeCurrentWork() {
        BroadcastDispatchQueue<String> queue = new BroadcastDispatchQueue<>(2, 4);
        Object owner = new Object();
        queue.enqueue(owner, 9, 1, "current");

        assertEquals(BroadcastDispatchQueue.EnqueueResult.STALE_GENERATION,
                queue.enqueue(owner, 10, 2, "stale-owner"));
        assertNull(queue.takeNext(owner, 8));
        assertEquals("current", queue.takeNext(owner, 9).value);
        assertNull(queue.complete(owner, 8, 1));
        assertNull(queue.complete(owner, 9, 2));
        assertEquals(1, queue.size());
    }

    @Test
    public void deathOrStopCancelsInFlightAndQueuedExactlyOnce() {
        BroadcastDispatchQueue<String> queue = new BroadcastDispatchQueue<>(3, 6);
        Object owner = new Object();
        queue.enqueue(owner, 4, 1, "active");
        queue.enqueue(owner, 4, 2, "pending");
        queue.takeNext(owner, 4);

        List<BroadcastDispatchQueue.Item<String>> cancelled = queue.cancel(owner, 4);

        assertEquals(2, cancelled.size());
        assertEquals("active", cancelled.get(0).value);
        assertEquals("pending", cancelled.get(1).value);
        assertEquals(0, queue.size());
        assertFalse(queue.hasInFlight(owner));
        assertTrue(queue.cancel(owner, 4).isEmpty());
    }

    @Test
    public void timeoutCanRemoveQueuedItemWithoutDisturbingInflightOwner() {
        BroadcastDispatchQueue<String> queue = new BroadcastDispatchQueue<>(3, 6);
        Object owner = new Object();
        queue.enqueue(owner, 5, 1, "active");
        queue.enqueue(owner, 5, 2, "queued");
        queue.takeNext(owner, 5);

        assertEquals("queued", queue.remove(owner, 5, 2).value);
        assertTrue(queue.hasInFlight(owner));
        assertEquals(1, queue.size());
        assertEquals("active", queue.complete(owner, 5, 1).value);
        assertEquals(0, queue.size());
    }
}
