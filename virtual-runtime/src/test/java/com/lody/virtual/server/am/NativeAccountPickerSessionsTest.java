package com.lody.virtual.server.am;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class NativeAccountPickerSessionsTest {
    private static String issue(NativeAccountPickerSessions<Object> sessions, Object owner) {
        return sessions.issue(owner, owner, 7, 3, "fixture.pkg", 4, 10, 20, 1000);
    }
    @Test public void destroyedForwardingActivityDoesNotInvalidateLiveOriginalOwner() {
        Object owner = new Object();
        NativeAccountPickerSessions<Object> sessions = new NativeAccountPickerSessions<>(s -> s.owner == owner && s.processGeneration == 7);
        String id = issue(sessions, owner);
        assertNotNull(id);
        assertEquals(-1, sessions.current(id, 1001));
        assertEquals(3, sessions.claim(id, 1001));
        // No Activity token is retained after server-authenticated issue. A forwarding trampoline
        // may finish, while the exact original process and Space remain authoritative.
        assertEquals(3, sessions.current(id, 5000));
        assertTrue(sessions.complete(id, 5001));
        assertFalse(sessions.complete(id, 5002));
        assertEquals(-1, sessions.current(id, 5002));
    }
    @Test public void claimIsSingleUseButRecreationCanCheckSameClaim() {
        Object owner = new Object();
        NativeAccountPickerSessions<Object> sessions = new NativeAccountPickerSessions<>(s -> true);
        String id = issue(sessions, owner);
        assertEquals(3, sessions.claim(id, 1001));
        assertEquals(-1, sessions.claim(id, 1002));
        assertEquals(3, sessions.current(id, 1003));
    }
    @Test public void processDeathStopOrScopeReplacementRejectsLateResult() {
        Object owner = new Object();
        AtomicBoolean current = new AtomicBoolean(true);
        NativeAccountPickerSessions<Object> sessions = new NativeAccountPickerSessions<>(s -> current.get());
        String id = issue(sessions, owner);
        assertEquals(3, sessions.claim(id, 1001));
        current.set(false);
        assertFalse(sessions.complete(id, 1002));
        current.set(true);
        assertEquals(-1, sessions.current(id, 1003));
    }
    @Test public void exactOwnerRetirementCannotAffectOtherSpace() {
        Object owner = new Object(); Object other = new Object();
        NativeAccountPickerSessions<Object> sessions = new NativeAccountPickerSessions<>(s -> true);
        String id = issue(sessions, owner);
        String otherId = sessions.issue(other, other, 8, 5, "fixture.pkg", 6, 11, 21, 1000);
        assertEquals(3, sessions.claim(id, 1001)); assertEquals(5, sessions.claim(otherId, 1001));
        sessions.retireOwner(owner);
        assertEquals(-1, sessions.current(id, 1002)); assertEquals(5, sessions.current(otherId, 1002));
        sessions.retireUser(5);
        assertFalse(sessions.complete(otherId, 1003));
    }
    @Test public void deadlineIsHardAndUnclaimedCancellationDoesNotAuthorizeResult() {
        Object owner = new Object();
        NativeAccountPickerSessions<Object> sessions = new NativeAccountPickerSessions<>(s -> true);
        String id = issue(sessions, owner);
        assertEquals(3, sessions.claim(id, 1001));
        assertEquals(3, sessions.current(id, 120999));
        assertEquals(-1, sessions.current(id, 121000));
        assertFalse(sessions.complete(id, 121001));
        String unclaimed = issue(sessions, owner);
        assertFalse(sessions.complete(unclaimed, 1001));
        assertEquals(-1, sessions.claim(unclaimed, 1002));
    }
    @Test public void invalidOwnerAndMissingEpochFailClosed() {
        Object owner = new Object();
        NativeAccountPickerSessions<Object> sessions = new NativeAccountPickerSessions<>(s -> false);
        assertNull(issue(sessions, owner));
        NativeAccountPickerSessions<Object> valid = new NativeAccountPickerSessions<>(s -> true);
        assertNull(valid.issue(owner, owner, 7, 3, "fixture.pkg", 4, 0, 0, 1000));
        // Legacy primary users can have epoch zero; serial and exact owner remain required.
        assertNotNull(valid.issue(owner, owner, 7, 0, "fixture.pkg", 0, 0, 20, 1000));
    }
}
