package com.lody.virtual.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

public class PreparedActivityLaunchTest {
    @Test
    public void failureNeverContainsHostWork() {
        PreparedActivityLaunch failure = PreparedActivityLaunch.failure("rejected");

        assertFalse(failure.isSuccess());
        assertNull(failure.getIntent());
        assertNull(failure.getLaunchId());
        assertEquals(-1, failure.getTaskId());
    }

    @Test
    public void reusedContainsOnlyPhysicalTaskAndAcknowledgementId() {
        PreparedActivityLaunch reused = PreparedActivityLaunch.reused(42, "launch-reuse");

        assertTrue(reused.isSuccess());
        assertTrue(reused.isReused());
        assertFalse(reused.isHostStartRequired());
        assertNull(reused.getIntent());
        assertEquals(42, reused.getTaskId());
        assertEquals("launch-reuse", reused.getLaunchId());
    }

    @Test
    public void newLaunchReturnsDefensiveHostStartIntent() {
        Intent source = new Intent("test.launch");
        PreparedActivityLaunch launch =
                PreparedActivityLaunch.hostStartRequired(source, "launch-new");

        assertTrue(launch.isHostStartRequired());
        assertFalse(launch.isReused());
        assertEquals("launch-new", launch.getLaunchId());
        assertNotSame(source, launch.getIntent());
        assertNotSame(launch.getIntent(), launch.getIntent());
    }
}
