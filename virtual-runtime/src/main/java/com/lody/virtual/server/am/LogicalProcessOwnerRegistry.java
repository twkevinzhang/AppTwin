package com.lody.virtual.server.am;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure-Java ownership gate for logical guest processes and their host stub slots.
 *
 * <p>Every mutation is serialized on this registry. Starting a process is a two-phase operation:
 * callers first reserve a key and slot, then atomically claim that reservation with the process
 * identity. A live owner is never displaced. Dead owners may be replaced, but only by a newer
 * generation. Generation high-water marks survive removal so a late death or reconciliation
 * callback cannot affect a successor.</p>
 *
 * <p>Owner identity comparisons intentionally use {@code ==}. A process record or binder wrapper
 * is an ownership capability, not a value object.</p>
 */
final class LogicalProcessOwnerRegistry<T> {

    interface Liveness<T> {
        boolean isAlive(T owner);
    }

    enum ReservationStatus {
        RESERVED,
        EXISTING_OWNER,
        KEY_BUSY,
        SLOT_BUSY
    }

    enum ClaimStatus {
        CLAIMED,
        ALREADY_OWNED,
        REJECTED_LIVE_OWNER,
        STALE_RESERVATION,
        SLOT_BUSY
    }

    enum ReconcileStatus {
        RECONCILED,
        ALREADY_OWNED,
        REJECTED_LIVE_OWNER,
        STALE_GENERATION,
        KEY_BUSY,
        SLOT_BUSY
    }

    static final class Reservation {
        private final LogicalProcessKey key;
        private final int slot;
        private final long generation;

        private Reservation(LogicalProcessKey key, int slot, long generation) {
            this.key = key;
            this.slot = slot;
            this.generation = generation;
        }

        LogicalProcessKey key() {
            return key;
        }

        int slot() {
            return slot;
        }

        long generation() {
            return generation;
        }
    }

    static final class OwnerSnapshot<T> {
        private final LogicalProcessKey key;
        private final int slot;
        private final long generation;
        private final T owner;

        private OwnerSnapshot(LogicalProcessKey key, int slot, long generation, T owner) {
            this.key = key;
            this.slot = slot;
            this.generation = generation;
            this.owner = owner;
        }

        LogicalProcessKey key() {
            return key;
        }

        int slot() {
            return slot;
        }

        long generation() {
            return generation;
        }

        T owner() {
            return owner;
        }
    }

    static final class ReservationResult<T> {
        private final ReservationStatus status;
        private final Reservation reservation;
        private final OwnerSnapshot<T> existingOwner;

        private ReservationResult(ReservationStatus status, Reservation reservation,
                                  OwnerSnapshot<T> existingOwner) {
            this.status = status;
            this.reservation = reservation;
            this.existingOwner = existingOwner;
        }

        ReservationStatus status() {
            return status;
        }

        Reservation reservation() {
            return reservation;
        }

        OwnerSnapshot<T> existingOwner() {
            return existingOwner;
        }
    }

    static final class ClaimResult<T> {
        private final ClaimStatus status;
        private final OwnerSnapshot<T> owner;

        private ClaimResult(ClaimStatus status, OwnerSnapshot<T> owner) {
            this.status = status;
            this.owner = owner;
        }

        ClaimStatus status() {
            return status;
        }

        OwnerSnapshot<T> owner() {
            return owner;
        }
    }

    static final class ReconcileResult<T> {
        private final ReconcileStatus status;
        private final OwnerSnapshot<T> owner;

        private ReconcileResult(ReconcileStatus status, OwnerSnapshot<T> owner) {
            this.status = status;
            this.owner = owner;
        }

        ReconcileStatus status() {
            return status;
        }

        OwnerSnapshot<T> owner() {
            return owner;
        }
    }

    private static final class Entry<T> {
        final LogicalProcessKey key;
        final int slot;
        final long generation;
        final Reservation reservation;
        final T owner;

        Entry(Reservation reservation) {
            this(reservation.key, reservation.slot, reservation.generation, reservation, null);
        }

        Entry(LogicalProcessKey key, int slot, long generation, T owner) {
            this(key, slot, generation, null, owner);
        }

        private Entry(LogicalProcessKey key, int slot, long generation,
                      Reservation reservation, T owner) {
            this.key = key;
            this.slot = slot;
            this.generation = generation;
            this.reservation = reservation;
            this.owner = owner;
        }

        boolean isReservation() {
            return reservation != null;
        }
    }

    private final Liveness<T> liveness;
    private final Map<LogicalProcessKey, Entry<T>> byKey = new HashMap<>();
    private final Map<Integer, Entry<T>> bySlot = new HashMap<>();
    private final Map<LogicalProcessKey, Long> keyGenerationHighWater = new HashMap<>();
    private final Map<Integer, Long> slotGenerationHighWater = new HashMap<>();
    private long nextGeneration = 1;

    LogicalProcessOwnerRegistry(Liveness<T> liveness) {
        if (liveness == null) {
            throw new NullPointerException("liveness");
        }
        this.liveness = liveness;
    }

    synchronized ReservationResult<T> reserve(LogicalProcessKey key, int slot) {
        requireKeyAndSlot(key, slot);
        Entry<T> keyEntry = byKey.get(key);
        if (keyEntry != null) {
            if (keyEntry.isReservation()) {
                return reservationResult(ReservationStatus.KEY_BUSY, null, null);
            }
            if (liveness.isAlive(keyEntry.owner)) {
                return reservationResult(ReservationStatus.EXISTING_OWNER, null,
                        snapshot(keyEntry));
            }
            removeEntry(keyEntry);
        }

        Entry<T> slotEntry = bySlot.get(slot);
        if (slotEntry != null) {
            if (slotEntry.isReservation() || liveness.isAlive(slotEntry.owner)) {
                return reservationResult(ReservationStatus.SLOT_BUSY, null,
                        slotEntry.isReservation() ? null : snapshot(slotEntry));
            }
            removeEntry(slotEntry);
        }

        long generation = allocateGeneration();
        Reservation reservation = new Reservation(key, slot, generation);
        putEntry(new Entry<T>(reservation));
        return reservationResult(ReservationStatus.RESERVED, reservation, null);
    }

    synchronized ClaimResult<T> claim(Reservation reservation, T candidate) {
        if (reservation == null) {
            throw new NullPointerException("reservation");
        }
        if (candidate == null) {
            throw new NullPointerException("candidate");
        }
        Entry<T> keyEntry = byKey.get(reservation.key);
        if (keyEntry != null && !keyEntry.isReservation()
                && keyEntry.generation == reservation.generation) {
            if (keyEntry.owner == candidate) {
                return claimResult(ClaimStatus.ALREADY_OWNED, snapshot(keyEntry));
            }
            if (liveness.isAlive(keyEntry.owner)) {
                return claimResult(ClaimStatus.REJECTED_LIVE_OWNER, snapshot(keyEntry));
            }
            return claimResult(ClaimStatus.STALE_RESERVATION, snapshot(keyEntry));
        }
        if (keyEntry == null || keyEntry.reservation != reservation) {
            return claimResult(ClaimStatus.STALE_RESERVATION,
                    keyEntry == null || keyEntry.isReservation() ? null : snapshot(keyEntry));
        }
        Entry<T> slotEntry = bySlot.get(reservation.slot);
        if (slotEntry != keyEntry) {
            return claimResult(ClaimStatus.SLOT_BUSY,
                    slotEntry == null || slotEntry.isReservation() ? null : snapshot(slotEntry));
        }

        Entry<T> owned = new Entry<>(reservation.key, reservation.slot,
                reservation.generation, candidate);
        putEntry(owned);
        return claimResult(ClaimStatus.CLAIMED, snapshot(owned));
    }

    /**
     * Restores a process observed outside the registry, for example by querying a live stub.
     * The supplied generation must be newer than every retired generation for both key and slot,
     * unless it exactly matches the active reservation being completed.
     */
    synchronized ReconcileResult<T> reconcile(LogicalProcessKey key, int slot, long generation,
                                               T candidate) {
        requireKeyAndSlot(key, slot);
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        if (candidate == null) {
            throw new NullPointerException("candidate");
        }

        Entry<T> keyEntry = byKey.get(key);
        if (keyEntry != null) {
            if (keyEntry.isReservation()) {
                if (keyEntry.slot == slot && keyEntry.generation == generation) {
                    Entry<T> owned = new Entry<>(key, slot, generation, candidate);
                    putEntry(owned);
                    advanceNextGeneration(generation);
                    return reconcileResult(ReconcileStatus.RECONCILED, snapshot(owned));
                }
                return reconcileResult(ReconcileStatus.KEY_BUSY, null);
            }
            if (keyEntry.slot == slot && keyEntry.generation == generation
                    && keyEntry.owner == candidate) {
                return reconcileResult(ReconcileStatus.ALREADY_OWNED, snapshot(keyEntry));
            }
            if (liveness.isAlive(keyEntry.owner)) {
                return reconcileResult(ReconcileStatus.REJECTED_LIVE_OWNER, snapshot(keyEntry));
            }
            if (generation <= keyEntry.generation) {
                return reconcileResult(ReconcileStatus.STALE_GENERATION, snapshot(keyEntry));
            }
        }

        Entry<T> slotEntry = bySlot.get(slot);
        if (slotEntry != null && slotEntry != keyEntry) {
            if (slotEntry.isReservation()) {
                return reconcileResult(ReconcileStatus.SLOT_BUSY, null);
            }
            if (liveness.isAlive(slotEntry.owner)) {
                return reconcileResult(ReconcileStatus.SLOT_BUSY, snapshot(slotEntry));
            }
            if (generation <= slotEntry.generation) {
                return reconcileResult(ReconcileStatus.STALE_GENERATION, snapshot(slotEntry));
            }
        }

        if (generation <= highWater(keyGenerationHighWater, key)
                || generation <= highWater(slotGenerationHighWater, slot)) {
            return reconcileResult(ReconcileStatus.STALE_GENERATION, null);
        }

        if (keyEntry != null) {
            removeEntry(keyEntry);
        }
        if (slotEntry != null && slotEntry != keyEntry) {
            removeEntry(slotEntry);
        }
        Entry<T> reconciled = new Entry<>(key, slot, generation, candidate);
        putEntry(reconciled);
        advanceNextGeneration(generation);
        return reconcileResult(ReconcileStatus.RECONCILED, snapshot(reconciled));
    }

    /** Removes only the exact owner capability and generation named by the callback. */
    synchronized boolean remove(LogicalProcessKey key, long generation, T expectedOwner) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (expectedOwner == null) {
            throw new NullPointerException("expectedOwner");
        }
        Entry<T> entry = byKey.get(key);
        if (entry == null || entry.isReservation()
                || entry.generation != generation || entry.owner != expectedOwner) {
            return false;
        }
        removeEntry(entry);
        return true;
    }

    /** Cancels only the exact reservation capability; an old startup failure cannot cancel a new one. */
    synchronized boolean cancel(Reservation reservation) {
        if (reservation == null) {
            throw new NullPointerException("reservation");
        }
        Entry<T> entry = byKey.get(reservation.key);
        if (entry == null || entry.reservation != reservation) {
            return false;
        }
        removeEntry(entry);
        return true;
    }

    synchronized OwnerSnapshot<T> find(LogicalProcessKey key) {
        Entry<T> entry = byKey.get(key);
        return entry == null || entry.isReservation() ? null : snapshot(entry);
    }

    /** Returns the claimed owner only while its endpoint is still usable. */
    synchronized OwnerSnapshot<T> findLive(LogicalProcessKey key) {
        Entry<T> entry = byKey.get(key);
        return entry == null || entry.isReservation() || !liveness.isAlive(entry.owner)
                ? null : snapshot(entry);
    }

    synchronized OwnerSnapshot<T> findBySlot(int slot) {
        Entry<T> entry = bySlot.get(slot);
        return entry == null || entry.isReservation() ? null : snapshot(entry);
    }

    /** Returns only the immutable capability currently reserving this physical slot. */
    synchronized Reservation findReservationBySlot(int slot) {
        Entry<T> entry = bySlot.get(slot);
        return entry == null || !entry.isReservation() ? null : entry.reservation;
    }

    synchronized int ownerCount() {
        int count = 0;
        for (Entry<T> entry : byKey.values()) {
            if (!entry.isReservation()) {
                count++;
            }
        }
        return count;
    }

    private void putEntry(Entry<T> entry) {
        byKey.put(entry.key, entry);
        bySlot.put(entry.slot, entry);
        recordHighWater(entry.key, entry.slot, entry.generation);
    }

    private void removeEntry(Entry<T> entry) {
        if (byKey.get(entry.key) == entry) {
            byKey.remove(entry.key);
        }
        if (bySlot.get(entry.slot) == entry) {
            bySlot.remove(entry.slot);
        }
        recordHighWater(entry.key, entry.slot, entry.generation);
    }

    private void recordHighWater(LogicalProcessKey key, int slot, long generation) {
        if (generation > highWater(keyGenerationHighWater, key)) {
            keyGenerationHighWater.put(key, generation);
        }
        if (generation > highWater(slotGenerationHighWater, slot)) {
            slotGenerationHighWater.put(slot, generation);
        }
    }

    private long allocateGeneration() {
        if (nextGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("logical process generation exhausted");
        }
        return nextGeneration++;
    }

    private void advanceNextGeneration(long generation) {
        if (generation == Long.MAX_VALUE) {
            nextGeneration = Long.MAX_VALUE;
        } else if (nextGeneration <= generation) {
            nextGeneration = generation + 1;
        }
    }

    private static <K> long highWater(Map<K, Long> highWater, K key) {
        Long value = highWater.get(key);
        return value == null ? -1 : value;
    }

    private static void requireKeyAndSlot(LogicalProcessKey key, int slot) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
    }

    private static <T> OwnerSnapshot<T> snapshot(Entry<T> entry) {
        return new OwnerSnapshot<>(entry.key, entry.slot, entry.generation, entry.owner);
    }

    private static <T> ReservationResult<T> reservationResult(
            ReservationStatus status, Reservation reservation, OwnerSnapshot<T> existingOwner) {
        return new ReservationResult<>(status, reservation, existingOwner);
    }

    private static <T> ClaimResult<T> claimResult(ClaimStatus status, OwnerSnapshot<T> owner) {
        return new ClaimResult<>(status, owner);
    }

    private static <T> ReconcileResult<T> reconcileResult(
            ReconcileStatus status, OwnerSnapshot<T> owner) {
        return new ReconcileResult<>(status, owner);
    }
}
