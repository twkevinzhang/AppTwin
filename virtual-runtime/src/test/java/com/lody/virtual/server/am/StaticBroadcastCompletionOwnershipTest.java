package com.lody.virtual.server.am;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class StaticBroadcastCompletionOwnershipTest {
    @Test
    public void exactCapabilityTokenAndGenerationConsumeOwnerOnce() {
        StaticBroadcastDispatcher.CompletionOwnershipRegistry<Object> registry =
                new StaticBroadcastDispatcher.CompletionOwnershipRegistry<>();
        Object capability = new Object();
        Object owner = new Object();
        registry.register(capability, owner, 41L, 7L);

        assertSame(owner, registry.take(capability, 41L, 7L));
        assertNull(registry.take(capability, 41L, 7L));
        assertEquals(0, registry.size());
    }

    @Test
    public void forgedTupleDoesNotConsumeLegitimateOwnership() {
        StaticBroadcastDispatcher.CompletionOwnershipRegistry<Object> registry =
                new StaticBroadcastDispatcher.CompletionOwnershipRegistry<>();
        Object capability = new Object();
        Object owner = new Object();
        registry.register(capability, owner, 9L, 3L);

        assertNull(registry.take(new Object(), 9L, 3L));
        assertNull(registry.take(capability, 10L, 3L));
        assertNull(registry.take(capability, 9L, 4L));
        assertEquals(1, registry.size());
        assertSame(owner, registry.take(capability, 9L, 3L));
    }

    @Test
    public void timeoutOrCancellationRevokesLateAck() {
        StaticBroadcastDispatcher.CompletionOwnershipRegistry<Object> registry =
                new StaticBroadcastDispatcher.CompletionOwnershipRegistry<>();
        Object capability = new Object();
        registry.register(capability, new Object(), 5L, 2L);

        registry.revoke(capability);

        assertNull(registry.take(capability, 5L, 2L));
        assertEquals(0, registry.size());
    }
}
