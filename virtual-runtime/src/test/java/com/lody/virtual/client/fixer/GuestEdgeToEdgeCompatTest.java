package com.lody.virtual.client.fixer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuestEdgeToEdgeCompatTest {

    @Test
    public void skipsBeforeAndroid15() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(34, 34, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(34, 35, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(34, 36, false, false));
    }

    @Test
    public void skipsGuestsTargetingBeforeAndroid15() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 34, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(36, 34, false, false));
    }

    @Test
    public void android15RespectsOptOut() {
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(35, 35, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 35, false, true));
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(35, 36, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 36, false, true));
    }

    @Test
    public void android16AndLaterRespectOptOutForTarget35Guest() {
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(36, 35, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(36, 35, false, true));
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(37, 35, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(37, 35, false, true));
    }

    @Test
    public void android16AndLaterIgnoreOptOutForTarget36Guest() {
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(36, 36, false, false));
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(36, 36, false, true));
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(37, 36, false, false));
        assertTrue(GuestEdgeToEdgeCompat.shouldApply(37, 36, false, true));
    }

    @Test
    public void skipsFloatingGuest() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 35, true, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(36, 36, true, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(36, 36, true, true));
    }

    @Test
    public void missingGuestInfoOrThemeLookupFailsClosed() {
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, null, false, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 35, null, false));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(35, 35, false, null));
        assertFalse(GuestEdgeToEdgeCompat.shouldApply(36, 36, false, null));
    }
}
