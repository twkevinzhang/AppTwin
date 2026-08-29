package com.lody.virtual.client.fixer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuestEdgeToEdgeCompatTest {

    @Test
    public void skipsBeforeAndroid15() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(34, 36, false));
    }

    @Test
    public void skipsGuestsTargetingBeforeAndroid15() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 34, false));
    }

    @Test
    public void appliesAtAndroid15BoundaryForNonFloatingGuest() {
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(35, 35, false));
    }

    @Test
    public void appliesOnLaterAndroidForEligibleNonFloatingGuest() {
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(37, 36, false));
    }

    @Test
    public void skipsFloatingGuest() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 35, true));
    }

    @Test
    public void missingGuestInfoOrThemeLookupFailsClosed() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, null, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 35, null));
    }
}
