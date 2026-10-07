package com.lody.virtual.server.accounts;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CacheGenerationFenceTest {
    @Test public void cleanupCanFinishDuringLoadAndStaleCandidateCannotPublish() throws Exception {
        Object cacheLock = new Object();
        CacheGenerationFence fence = new CacheGenerationFence();
        CountDownLatch loading = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread loader = new Thread(() -> {
            try {
            final long observed;
            synchronized (cacheLock) { observed = fence.snapshot(); }
            loading.countDown();
            assertTrue(cleaned.await(2, TimeUnit.SECONDS));
            synchronized (cacheLock) { published.set(fence.isCurrent(observed)); }
            } catch (Throwable failure) { workerFailure.set(failure); }
        });
        loader.start();
        assertTrue(loading.await(2, TimeUnit.SECONDS));
        synchronized (cacheLock) { fence.invalidate(); }
        cleaned.countDown();
        loader.join(2000);
        assertFalse("Load must not block cleanup or survive its invalidation", loader.isAlive());
        assertNull(workerFailure.get());
        assertFalse("Pre-cleanup cache must never resurrect", published.get());
        synchronized (cacheLock) { assertTrue(fence.isCurrent(fence.snapshot())); }
    }
}
