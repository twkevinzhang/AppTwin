package com.lody.virtual.client.isolated;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds synchronous guest callbacks so a wedged child cannot pin its Binder caller forever. */
public final class IsolatedWorkerCallGate {
    public static final long CALL_TIMEOUT_MILLIS = 10_000L;

    private IsolatedWorkerCallGate() {
    }

    public static <T> T await(FutureTask<T> task) {
        return await(task, CALL_TIMEOUT_MILLIS);
    }

    static <T> T await(FutureTask<T> task, long timeoutMillis) {
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw new IllegalStateException("isolated guest callback timed out", error);
        } catch (InterruptedException error) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("isolated guest callback interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("isolated guest callback failed", cause);
        }
    }
}
