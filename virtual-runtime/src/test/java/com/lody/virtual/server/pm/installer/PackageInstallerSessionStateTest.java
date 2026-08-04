package com.lody.virtual.server.pm.installer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class PackageInstallerSessionStateTest {
    @Test
    public void repeatedCommitIsRejected() {
        PackageInstallerSessionState state = new PackageInstallerSessionState();
        state.requestCommit(false);

        try {
            state.requestCommit(false);
            fail("A repeated commit must be rejected");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    @Test
    public void completionIsDispatchedOnlyOnceAfterFailureOrCancellationRace() {
        PackageInstallerSessionState state = new PackageInstallerSessionState();
        state.requestCommit(false);

        assertTrue(state.markFinished());
        assertFalse(state.markFinished());
    }

    @Test(expected = IllegalStateException.class)
    public void destroyedSessionCannotCommit() {
        new PackageInstallerSessionState().requestCommit(true);
    }
}
