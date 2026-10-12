package com.lody.virtual.server.am;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded outstanding Binder probe. A stuck guest cannot accumulate queued probe threads. */
final class BoundedMcsProbe {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            0, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "McsHealthProbe");
                thread.setDaemon(true);
                return thread;
            });

    static long[] read(Callable<long[]> probe) {
        Future<long[]> future = null;
        try {
            future = EXECUTOR.submit(probe);
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return new long[]{0, 0, 0};
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }
}
