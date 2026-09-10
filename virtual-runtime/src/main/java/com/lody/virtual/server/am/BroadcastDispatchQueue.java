package com.lody.virtual.server.am;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/** Pure bounded FIFO with one in-flight broadcast per process owner. */
final class BroadcastDispatchQueue<T> {
    enum EnqueueResult {
        ACCEPTED,
        PROCESS_FULL,
        GLOBAL_FULL,
        STALE_GENERATION
    }

    static final class Item<T> {
        final long token;
        final long generation;
        final T value;

        Item(long token, long generation, T value) {
            this.token = token;
            this.generation = generation;
            this.value = value;
        }
    }

    private static final class OwnerQueue<T> {
        final long generation;
        final ArrayDeque<Item<T>> pending = new ArrayDeque<>();
        Item<T> inFlight;

        OwnerQueue(long generation) {
            this.generation = generation;
        }

        int size() {
            return pending.size() + (inFlight == null ? 0 : 1);
        }
    }

    private final int perProcessLimit;
    private final int globalLimit;
    private final Map<Object, OwnerQueue<T>> queues = new IdentityHashMap<>();
    private int size;

    BroadcastDispatchQueue(int perProcessLimit, int globalLimit) {
        if (perProcessLimit <= 0 || globalLimit < perProcessLimit) {
            throw new IllegalArgumentException("invalid queue limits");
        }
        this.perProcessLimit = perProcessLimit;
        this.globalLimit = globalLimit;
    }

    synchronized EnqueueResult enqueue(Object owner, long generation, long token, T value) {
        if (owner == null || value == null || token <= 0 || generation < 0) {
            throw new IllegalArgumentException("owner, token, generation and value are required");
        }
        OwnerQueue<T> queue = queues.get(owner);
        if (queue != null && queue.generation != generation) {
            return EnqueueResult.STALE_GENERATION;
        }
        if (queue != null && queue.size() >= perProcessLimit) {
            return EnqueueResult.PROCESS_FULL;
        }
        if (size >= globalLimit) {
            return EnqueueResult.GLOBAL_FULL;
        }
        if (queue == null) {
            queue = new OwnerQueue<>(generation);
            queues.put(owner, queue);
        }
        queue.pending.addLast(new Item<>(token, generation, value));
        size++;
        return EnqueueResult.ACCEPTED;
    }

    synchronized Item<T> takeNext(Object owner, long generation) {
        OwnerQueue<T> queue = queues.get(owner);
        if (queue == null || queue.generation != generation || queue.inFlight != null) {
            return null;
        }
        queue.inFlight = queue.pending.pollFirst();
        return queue.inFlight;
    }

    synchronized Item<T> complete(Object owner, long generation, long token) {
        OwnerQueue<T> queue = queues.get(owner);
        if (queue == null || queue.generation != generation || queue.inFlight == null
                || queue.inFlight.token != token) {
            return null;
        }
        Item<T> completed = queue.inFlight;
        queue.inFlight = null;
        size--;
        removeIfEmpty(owner, queue);
        return completed;
    }

    /** Removes one timed-out item whether it is queued or currently in flight. */
    synchronized Item<T> remove(Object owner, long generation, long token) {
        OwnerQueue<T> queue = queues.get(owner);
        if (queue == null || queue.generation != generation) return null;
        if (queue.inFlight != null && queue.inFlight.token == token) {
            Item<T> removed = queue.inFlight;
            queue.inFlight = null;
            size--;
            removeIfEmpty(owner, queue);
            return removed;
        }
        java.util.Iterator<Item<T>> iterator = queue.pending.iterator();
        while (iterator.hasNext()) {
            Item<T> item = iterator.next();
            if (item.token == token) {
                iterator.remove();
                size--;
                removeIfEmpty(owner, queue);
                return item;
            }
        }
        return null;
    }

    synchronized java.util.List<Item<T>> cancel(Object owner, long generation) {
        OwnerQueue<T> queue = queues.get(owner);
        java.util.List<Item<T>> cancelled = new java.util.ArrayList<>();
        if (queue == null || queue.generation != generation) {
            return cancelled;
        }
        queues.remove(owner);
        if (queue.inFlight != null) {
            cancelled.add(queue.inFlight);
        }
        cancelled.addAll(queue.pending);
        size -= queue.size();
        return cancelled;
    }

    synchronized int ownerSize(Object owner) {
        OwnerQueue<T> queue = queues.get(owner);
        return queue == null ? 0 : queue.size();
    }

    synchronized int size() {
        return size;
    }

    synchronized boolean hasInFlight(Object owner) {
        OwnerQueue<T> queue = queues.get(owner);
        return queue != null && queue.inFlight != null;
    }

    private void removeIfEmpty(Object owner, OwnerQueue<T> queue) {
        if (queue.size() == 0) {
            queues.remove(owner);
        }
    }
}
