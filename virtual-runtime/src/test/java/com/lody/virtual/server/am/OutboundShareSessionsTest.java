package com.lody.virtual.server.am;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class OutboundShareSessionsTest {
    private final AtomicLong now = new AtomicLong(10_000L);
    private final List<String> discarded = new ArrayList<>();
    private final OutboundShareSessions<String> sessions =
            new OutboundShareSessions<>(now::get, discarded::add);

    @Test public void takeOnce() {
        String id = sessions.put("payload");
        assertEquals("payload", sessions.take(id));
        assertNull(sessions.take(id));
        assertTrue(discarded.isEmpty());
    }

    @Test public void expiredTakeReturnsNullAndDiscards() {
        String id = sessions.put("payload");
        now.addAndGet(OutboundShareSessions.TTL_MILLIS + 1);
        assertNull(sessions.take(id));
        assertEquals(1, discarded.size());
        assertEquals("payload", discarded.get(0));
    }

    @Test public void quotaRejects65th() {
        for (int i = 0; i < OutboundShareSessions.MAX_SESSIONS; i++) sessions.put("p" + i);
        assertEquals(64, OutboundShareSessions.MAX_SESSIONS);
        try {
            sessions.put("overflow");
            fail("expected quota rejection");
        } catch (SecurityException expected) {
            assertEquals(1, discarded.size());
            assertEquals("overflow", discarded.get(0));
        }
    }

    @Test public void expiredSessionsFreeQuota() {
        for (int i = 0; i < OutboundShareSessions.MAX_SESSIONS; i++) sessions.put("p" + i);
        now.addAndGet(OutboundShareSessions.TTL_MILLIS + 1);
        assertNotNull(sessions.put("fresh"));
        assertEquals(64, discarded.size());
    }

    @Test public void unknownIdReturnsNull() {
        assertNull(sessions.take("missing"));
        assertNull(sessions.take(null));
    }
}
