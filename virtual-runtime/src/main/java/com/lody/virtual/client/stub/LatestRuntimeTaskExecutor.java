package com.lody.virtual.client.stub;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One running runtime operation and at most one replacement, never an unbounded start queue. */
final class LatestRuntimeTaskExecutor {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "apptwin-daemon-reconcile");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    synchronized boolean submit(Runnable task) {
        if (executor.isShutdown()) return false;
        executor.getQueue().clear();
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            return false;
        }
    }

    synchronized void close() { executor.shutdownNow(); }
}
