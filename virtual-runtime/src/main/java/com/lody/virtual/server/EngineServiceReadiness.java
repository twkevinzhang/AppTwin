package com.lody.virtual.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Publishes a complete engine atomically to Binder clients without delaying provider publish. */
final class EngineServiceReadiness {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean ready;

    synchronized void complete(boolean succeeded) {
        if (completed.getCount() == 0) return;
        ready = succeeded;
        completed.countDown();
    }

    boolean await(long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative startup timeout");
        try {
            return completed.await(timeoutMillis, TimeUnit.MILLISECONDS) && ready;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
