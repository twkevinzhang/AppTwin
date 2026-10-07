package com.lody.virtual.server.accounts;

/** Used under the owning cache lock; slow package queries must run outside that lock. */
final class CacheGenerationFence {
    private long generation;
    long snapshot() { return generation; }
    void invalidate() { generation++; }
    boolean isCurrent(long observed) { return observed == generation; }
}
