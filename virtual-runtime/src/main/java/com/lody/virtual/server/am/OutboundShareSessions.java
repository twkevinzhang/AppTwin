package com.lody.virtual.server.am;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Bounded, single-use, expiring hand-off slots between a guest launch and the host bridge. */
final class OutboundShareSessions<P> {
    static final long TTL_MILLIS = 300000L;
    static final int MAX_SESSIONS = 64;

    private static final class Entry<P> {
        final P payload;
        final long deadline;
        Entry(P payload, long deadline) { this.payload = payload; this.deadline = deadline; }
    }

    private final LongSupplier clock;
    private final Consumer<P> onDiscard;
    private final Map<String, Entry<P>> entries = new LinkedHashMap<>();

    OutboundShareSessions(LongSupplier clock, Consumer<P> onDiscard) {
        this.clock = clock;
        this.onDiscard = onDiscard;
    }

    synchronized String put(P payload) {
        expire();
        if (entries.size() >= MAX_SESSIONS) {
            onDiscard.accept(payload);
            throw new SecurityException("Outbound session quota exceeded");
        }
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry<>(payload, clock.getAsLong() + TTL_MILLIS));
        return id;
    }

    synchronized P take(String id) {
        expire();
        if (id == null) return null;
        Entry<P> entry = entries.remove(id);
        return entry == null ? null : entry.payload;
    }

    private void expire() {
        long now = clock.getAsLong();
        Iterator<Entry<P>> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry<P> entry = it.next();
            if (entry.deadline < now) {
                it.remove();
                onDiscard.accept(entry.payload);
            }
        }
    }
}
