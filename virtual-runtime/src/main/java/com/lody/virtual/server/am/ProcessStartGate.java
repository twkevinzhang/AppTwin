package com.lody.virtual.server.am;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes logical process ownership while allowing Binder entry points to fail fast.
 *
 * <p>Blocking every Binder thread behind process creation can exhaust the Binder pool. A newly
 * spawned Stub then cannot call back into the server to finish publishing its provider, leaving
 * the lock owner and the Stub waiting on each other.</p>
 */
final class ProcessStartGate {

    private final ReentrantLock lock = new ReentrantLock();

    void enter() {
        lock.lock();
    }

    boolean tryEnter() {
        return lock.tryLock();
    }

    void exit() {
        lock.unlock();
    }
}
