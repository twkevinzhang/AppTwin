package com.lody.virtual.client.stub;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class LatestRuntimeTaskExecutorTest {
    @Test public void slowRuntimeDoesNotBlockCallerAndOnlyLatestPendingRequestRuns() throws Exception {
        LatestRuntimeTaskExecutor executor = new LatestRuntimeTaskExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch latest = new CountDownLatch(1);
        AtomicBoolean supersededRan = new AtomicBoolean();
        try {
            assertTrue(executor.submit(() -> {
                started.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(executor.submit(() -> supersededRan.set(true)));
            assertTrue(executor.submit(latest::countDown));
            release.countDown();
            assertTrue(latest.await(1, TimeUnit.SECONDS));
            assertFalse(supersededRan.get());
        } finally { release.countDown(); executor.close(); }
    }
    @Test public void destroyRejectsNewWorkAndCancelsQueuedWork() throws Exception {
        LatestRuntimeTaskExecutor executor = new LatestRuntimeTaskExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean pendingRan = new AtomicBoolean();
        executor.submit(() -> {
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        executor.submit(() -> pendingRan.set(true));
        executor.close();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertFalse(executor.submit(() -> {}));
        assertFalse(pendingRan.get());
    }
}
