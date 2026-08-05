package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LogicalProcessOwnerRegistryTest {

    private static final LogicalProcessKey LINE = new LogicalProcessKey(
            11062, "jp.naver.line.android", "jp.naver.line.android");

    private final LogicalProcessOwnerRegistry<TestOwner> registry =
            new LogicalProcessOwnerRegistry<>(owner -> owner.alive);

    @Test
    public void reservationClaimsOneKeyAndSlot() {
        LogicalProcessOwnerRegistry.ReservationResult<TestOwner> reserved =
                registry.reserve(LINE, 4);
        TestOwner owner = new TestOwner("line");

        LogicalProcessOwnerRegistry.ClaimResult<TestOwner> claimed =
                registry.claim(reserved.reservation(), owner);

        assertEquals(LogicalProcessOwnerRegistry.ReservationStatus.RESERVED, reserved.status());
        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED, claimed.status());
        assertSame(owner, registry.find(LINE).owner());
        assertSame(owner, registry.findBySlot(4).owner());
        assertEquals(1, registry.ownerCount());
    }

    @Test
    public void concurrentClaimHasExactlyOneWinner() throws Exception {
        LogicalProcessOwnerRegistry.Reservation reservation =
                registry.reserve(LINE, 3).reservation();
        TestOwner first = new TestOwner("first");
        TestOwner second = new TestOwner("second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<LogicalProcessOwnerRegistry.ClaimStatus> results =
                Collections.synchronizedList(new ArrayList<>());
        Thread firstThread = claimThread(reservation, first, ready, start, results);
        Thread secondThread = claimThread(reservation, second, ready, start, results);
        firstThread.start();
        secondThread.start();

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        firstThread.join(5_000);
        secondThread.join(5_000);

        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals(1, Collections.frequency(
                results, LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED));
        assertEquals(1, Collections.frequency(
                results, LogicalProcessOwnerRegistry.ClaimStatus.REJECTED_LIVE_OWNER));
        assertEquals(1, registry.ownerCount());
    }

    @Test
    public void sameOwnerClaimRetryIsIdempotent() {
        LogicalProcessOwnerRegistry.Reservation reservation =
                registry.reserve(LINE, 1).reservation();
        TestOwner owner = new TestOwner("line");
        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED,
                registry.claim(reservation, owner).status());

        LogicalProcessOwnerRegistry.ClaimResult<TestOwner> retried =
                registry.claim(reservation, owner);

        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.ALREADY_OWNED, retried.status());
        assertSame(owner, retried.owner().owner());
    }

    @Test
    public void liveOwnerRejectsNewReservationAndReconciliation() {
        TestOwner owner = claim(LINE, 2);
        long generation = registry.find(LINE).generation();
        TestOwner newcomer = new TestOwner("newcomer");

        LogicalProcessOwnerRegistry.ReservationResult<TestOwner> reservation =
                registry.reserve(LINE, 5);
        LogicalProcessOwnerRegistry.ReconcileResult<TestOwner> reconciliation =
                registry.reconcile(LINE, 5, generation + 1, newcomer);

        assertEquals(LogicalProcessOwnerRegistry.ReservationStatus.EXISTING_OWNER,
                reservation.status());
        assertSame(owner, reservation.existingOwner().owner());
        assertEquals(LogicalProcessOwnerRegistry.ReconcileStatus.REJECTED_LIVE_OWNER,
                reconciliation.status());
        assertSame(owner, registry.find(LINE).owner());
    }

    @Test
    public void deadOwnerIsReplacedByNewReservationGeneration() {
        TestOwner deadOwner = claim(LINE, 2);
        long deadGeneration = registry.find(LINE).generation();
        deadOwner.alive = false;

        LogicalProcessOwnerRegistry.Reservation replacement =
                registry.reserve(LINE, 2).reservation();
        TestOwner newOwner = new TestOwner("replacement");

        assertTrue(replacement.generation() > deadGeneration);
        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED,
                registry.claim(replacement, newOwner).status());
        assertSame(newOwner, registry.find(LINE).owner());
    }

    @Test
    public void slotCannotBeSharedByDifferentLogicalProcesses() {
        claim(LINE, 6);
        LogicalProcessKey push = new LogicalProcessKey(
                11062, "jp.naver.line.android", "jp.naver.line.android:push");

        LogicalProcessOwnerRegistry.ReservationResult<TestOwner> result =
                registry.reserve(push, 6);

        assertEquals(LogicalProcessOwnerRegistry.ReservationStatus.SLOT_BUSY, result.status());
        assertNull(registry.find(push));
    }

    @Test
    public void staleDeathCannotRemoveSuccessor() {
        TestOwner first = claim(LINE, 1);
        long firstGeneration = registry.find(LINE).generation();
        first.alive = false;
        TestOwner successor = claim(LINE, 1);
        long successorGeneration = registry.find(LINE).generation();

        assertFalse(registry.remove(LINE, firstGeneration, first));
        assertFalse(registry.remove(LINE, successorGeneration, first));
        assertSame(successor, registry.find(LINE).owner());
        assertTrue(registry.remove(LINE, successorGeneration, successor));
        assertNull(registry.find(LINE));
    }

    @Test
    public void registryMissCanReconcileObservedOwner() {
        TestOwner owner = new TestOwner("observed-stub");

        LogicalProcessOwnerRegistry.ReconcileResult<TestOwner> result =
                registry.reconcile(LINE, 7, 42, owner);

        assertEquals(LogicalProcessOwnerRegistry.ReconcileStatus.RECONCILED, result.status());
        assertEquals(42, result.owner().generation());
        assertSame(owner, registry.find(LINE).owner());
        assertSame(owner, registry.findBySlot(7).owner());
    }

    @Test
    public void retiredGenerationCannotReconcileAfterRemoval() {
        TestOwner retired = new TestOwner("retired");
        assertEquals(LogicalProcessOwnerRegistry.ReconcileStatus.RECONCILED,
                registry.reconcile(LINE, 0, 30, retired).status());
        assertTrue(registry.remove(LINE, 30, retired));

        LogicalProcessOwnerRegistry.ReconcileResult<TestOwner> stale =
                registry.reconcile(LINE, 0, 30, new TestOwner("stale"));
        LogicalProcessOwnerRegistry.ReconcileResult<TestOwner> current =
                registry.reconcile(LINE, 0, 31, new TestOwner("current"));

        assertEquals(LogicalProcessOwnerRegistry.ReconcileStatus.STALE_GENERATION,
                stale.status());
        assertEquals(LogicalProcessOwnerRegistry.ReconcileStatus.RECONCILED,
                current.status());
    }

    @Test
    public void cancelledReservationCannotCancelOrClaimSuccessor() {
        LogicalProcessOwnerRegistry.Reservation cancelled =
                registry.reserve(LINE, 8).reservation();
        assertTrue(registry.cancel(cancelled));
        LogicalProcessOwnerRegistry.Reservation successor =
                registry.reserve(LINE, 8).reservation();

        assertFalse(registry.cancel(cancelled));
        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.STALE_RESERVATION,
                registry.claim(cancelled, new TestOwner("late")).status());
        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED,
                registry.claim(successor, new TestOwner("successor")).status());
    }

    @Test
    public void differentVirtualUsersAndSecondaryProcessesRemainIndependent() {
        LogicalProcessKey anotherClone = new LogicalProcessKey(
                21062, "jp.naver.line.android", "jp.naver.line.android");
        LogicalProcessKey secondary = new LogicalProcessKey(
                11062, "jp.naver.line.android", "jp.naver.line.android:push");

        claim(LINE, 1);
        claim(anotherClone, 2);
        claim(secondary, 3);

        assertEquals(3, registry.ownerCount());
    }

    private TestOwner claim(LogicalProcessKey key, int slot) {
        LogicalProcessOwnerRegistry.Reservation reservation =
                registry.reserve(key, slot).reservation();
        TestOwner owner = new TestOwner(key.toString());
        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED,
                registry.claim(reservation, owner).status());
        return owner;
    }

    private Thread claimThread(LogicalProcessOwnerRegistry.Reservation reservation,
                               TestOwner owner, CountDownLatch ready, CountDownLatch start,
                               List<LogicalProcessOwnerRegistry.ClaimStatus> results) {
        return new Thread(() -> {
            ready.countDown();
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                results.add(registry.claim(reservation, owner).status());
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static final class TestOwner {
        final String name;
        volatile boolean alive = true;

        TestOwner(String name) {
            this.name = name;
        }
    }
}
