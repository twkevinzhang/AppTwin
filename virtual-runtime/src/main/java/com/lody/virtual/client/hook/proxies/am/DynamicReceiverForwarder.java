package com.lody.virtual.client.hook.proxies.am;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** Serial, exception-isolated handoff used by dynamic receiver Binder callbacks. */
final class DynamicReceiverForwarder {
    private final Executor executor;
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private boolean running;

    DynamicReceiverForwarder(Executor executor) {
        if (executor == null) throw new NullPointerException("executor");
        this.executor = executor;
    }

    void submit(Runnable task) {
        if (task == null) throw new NullPointerException("task");
        boolean schedule;
        synchronized (this) {
            pending.addLast(task);
            schedule = !running;
            if (schedule) running = true;
        }
        if (schedule) executor.execute(this::runNext);
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private void runNext() {
        Runnable task;
        synchronized (this) {
            task = pending.pollFirst();
            if (task == null) {
                running = false;
                return;
            }
        }
        try {
            task.run();
        } catch (Throwable ignored) {
            // A broken receiver must not strand later callbacks in the serial queue.
        } finally {
            executor.execute(this::runNext);
        }
    }
}
