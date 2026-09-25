package com.lody.virtual.server.am;

import com.lody.virtual.client.BroadcastBootstrap;
import org.junit.Test;
import static org.junit.Assert.*;

/** Executable cold-start regression: initialization must precede READY and receiver dispatch. */
public class BroadcastBootstrapTest {
    @Test public void coldStartRequestsInitializationOnceAndWaitsForRealReady() {
        BroadcastBootstrap.Lease lease = new BroadcastBootstrap.Lease();
        assertEquals(BroadcastBootstrap.Action.BOOTSTRAP, lease.next(false, true, 10, 100));
        assertEquals(BroadcastBootstrap.Action.WAIT, lease.next(false, true, 11, 100));
        assertEquals(BroadcastBootstrap.Action.DISPATCH, lease.next(true, true, 12, 100));
    }
    @Test public void warmStartNeverInitializesAgain() {
        assertEquals(BroadcastBootstrap.Action.DISPATCH,
                new BroadcastBootstrap.Lease().next(true, true, 10, 100));
    }
    @Test public void stopAndTimeoutFailClosedEvenAfterInitializationWasRequested() {
        BroadcastBootstrap.Lease lease = new BroadcastBootstrap.Lease();
        lease.next(false, true, 10, 100);
        assertEquals(BroadcastBootstrap.Action.REJECT, lease.next(true, false, 11, 100));
        assertEquals(BroadcastBootstrap.Action.REJECT, lease.next(true, true, 100, 100));
    }
    @Test public void cancelledOrExpiredGuestWorkCannotStart() {
        BroadcastBootstrap request = new BroadcastBootstrap(7, 100);
        request.cancel(7);
        assertFalse(request.start(7, 10, true));
        assertFalse(new BroadcastBootstrap(7, 100).start(7, 100, true));
    }
    @Test public void guestRejectsStaleOwnerAndCannotReplayOrRetryFailure() {
        BroadcastBootstrap request = new BroadcastBootstrap(7, 100);
        assertFalse(request.start(8, 10, true));
        assertFalse(request.start(7, 10, false));
        assertTrue(request.start(7, 10, true));
        assertFalse(request.start(7, 11, true));
    }
    @Test public void staleCancellationCannotCancelCurrentGeneration() {
        BroadcastBootstrap request = new BroadcastBootstrap(7, 100);
        request.cancel(6);
        assertTrue(request.start(7, 10, true));
    }
    @Test public void coldBootstrapDoesNotConsumeReceiverAndFifoWaitsForReady() {
        BroadcastDispatchQueue<String> queue = new BroadcastDispatchQueue<>(3, 6);
        Object owner = new Object();
        queue.enqueue(owner, 7, 1, "first");
        queue.enqueue(owner, 7, 2, "second");
        BroadcastBootstrap.Lease lease = new BroadcastBootstrap.Lease();
        assertEquals(BroadcastBootstrap.Action.BOOTSTRAP, lease.next(false, true, 10, 100));
        assertEquals("first", queue.peek(owner, 7).value);
        assertFalse(queue.hasInFlight(owner));
        assertEquals(BroadcastBootstrap.Action.WAIT, lease.next(false, true, 11, 100));
        assertEquals(2, queue.size());
        assertEquals(BroadcastBootstrap.Action.DISPATCH, lease.next(true, true, 12, 100));
        assertEquals("first", queue.takeNext(owner, 7).value);
        assertNull(queue.takeNext(owner, 7));
        queue.complete(owner, 7, 1);
        assertEquals("second", queue.takeNext(owner, 7).value);
    }
    @Test public void timeoutOrStopDrainsPendingBootstrapWithoutDispatch() {
        BroadcastDispatchQueue<String> queue = new BroadcastDispatchQueue<>(3, 6);
        Object owner = new Object();
        queue.enqueue(owner, 7, 1, "first");
        assertNull(queue.peek(owner, 8));
        BroadcastBootstrap.Lease lease = new BroadcastBootstrap.Lease();
        lease.next(false, true, 10, 100);
        assertEquals(BroadcastBootstrap.Action.REJECT, lease.next(false, true, 100, 100));
        assertEquals(1, queue.cancel(owner, 7).size());
        assertNull(queue.takeNext(owner, 7));
        assertEquals(0, queue.size());
    }
}
