package com.lody.virtual.server.am;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IsolatedServiceOwnerRegistryTest {
    private final LogicalProcessOwnerRegistry<Owner> registry =
            new LogicalProcessOwnerRegistry<>(owner -> owner.alive);

    @Test
    public void fiftyLogicalOwnersReceiveDistinctSlotsAndExhaustPool() {
        List<Owner> owners = new ArrayList<>();
        for (int slot = 0; slot < 50; slot++) {
            LogicalProcessKey key = key(100_000 + slot, "Service" + slot);
            LogicalProcessOwnerRegistry.Reservation reservation =
                    registry.reserve(key, slot).reservation();
            Owner owner = new Owner();
            owners.add(owner);
            assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED,
                    registry.claim(reservation, owner).status());
            assertSame(owner, registry.findBySlot(slot).owner());
        }

        LogicalProcessOwnerRegistry.ReservationResult<Owner> exhausted = null;
        LogicalProcessKey overflow = key(200_000, "OverflowService");
        for (int slot = 0; slot < 50; slot++) {
            exhausted = registry.reserve(overflow, slot);
            assertEquals(LogicalProcessOwnerRegistry.ReservationStatus.SLOT_BUSY,
                    exhausted.status());
        }
        assertEquals(50, registry.ownerCount());
        assertNull(registry.find(overflow));
    }

    @Test
    public void virtualUsersAndComponentsNeverShareAnOwner() {
        LogicalProcessKey firstAccount = key(110_005, "AttestationService");
        LogicalProcessKey secondAccount = key(210_005, "AttestationService");
        LogicalProcessKey secondComponent = key(110_005, "VideoService");

        Owner first = claim(firstAccount, 0);
        Owner second = claim(secondAccount, 1);
        Owner component = claim(secondComponent, 2);

        assertNotEquals(registry.find(firstAccount).slot(), registry.find(secondAccount).slot());
        assertNotEquals(registry.find(firstAccount).slot(), registry.find(secondComponent).slot());
        assertSame(first, registry.find(firstAccount).owner());
        assertSame(second, registry.find(secondAccount).owner());
        assertSame(component, registry.find(secondComponent).owner());
    }

    @Test
    public void deadWorkerCanBeReplacedButLateDeathCannotRemoveSuccessor() {
        LogicalProcessKey key = key(110_005, "AttestationService");
        Owner dead = claim(key, 7);
        long deadGeneration = registry.find(key).generation();
        dead.alive = false;
        Owner successor = claim(key, 7);
        long successorGeneration = registry.find(key).generation();

        assertTrue(successorGeneration > deadGeneration);
        assertFalse(registry.remove(key, deadGeneration, dead));
        assertSame(successor, registry.find(key).owner());
        assertTrue(registry.remove(key, successorGeneration, successor));
    }

    private Owner claim(LogicalProcessKey key, int slot) {
        LogicalProcessOwnerRegistry.Reservation reservation =
                registry.reserve(key, slot).reservation();
        Owner owner = new Owner();
        assertEquals(LogicalProcessOwnerRegistry.ClaimStatus.CLAIMED,
                registry.claim(reservation, owner).status());
        return owner;
    }

    private static LogicalProcessKey key(int vuid, String component) {
        return new LogicalProcessKey(vuid, "com.shopee.tw",
                "com.shopee.tw:wvvvuvww#com.shopee.shpssdk." + component);
    }

    private static final class Owner {
        volatile boolean alive = true;
    }
}
