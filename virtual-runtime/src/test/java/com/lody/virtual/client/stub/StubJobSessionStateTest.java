package com.lody.virtual.client.stub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class StubJobSessionStateTest {

    @Test
    public void boundSessionClaimsUnbindExactlyOnceAcrossRepeatedCleanup() {
        StubJob.SessionState state = new StubJob.SessionState();

        assertFalse(state.onBindingSucceeded());
        assertEquals(StubJob.SessionState.CleanupAction.CLEANED_AND_UNBIND,
                state.requestCleanup());
        assertEquals(StubJob.SessionState.CleanupAction.ALREADY_CLEANED,
                state.requestCleanup());
        assertFalse(state.onBindingSucceeded());
    }

    @Test
    public void cleanupDuringBindDefersSingleUnbindUntilBindSucceeds() {
        StubJob.SessionState state = new StubJob.SessionState();

        assertEquals(StubJob.SessionState.CleanupAction.CLEANED, state.requestCleanup());
        assertTrue(state.onBindingSucceeded());
        assertFalse(state.onBindingSucceeded());
        assertEquals(StubJob.SessionState.CleanupAction.ALREADY_CLEANED,
                state.requestCleanup());
    }

    @Test
    public void concurrentCleanupHasOneWinnerAndOneUnbind() throws Exception {
        StubJob.SessionState state = new StubJob.SessionState();
        assertFalse(state.onBindingSucceeded());
        int threadCount = 16;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<StubJob.SessionState.CleanupAction> actions =
                Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                actions.add(state.requestCleanup());
            });
            threads.add(thread);
            thread.start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000L);
            assertFalse(thread.isAlive());
        }

        assertEquals(1, Collections.frequency(actions,
                StubJob.SessionState.CleanupAction.CLEANED_AND_UNBIND));
        assertEquals(threadCount - 1, Collections.frequency(actions,
                StubJob.SessionState.CleanupAction.ALREADY_CLEANED));
    }

    @Test
    public void finishCallbackCanOnlyBeClaimedOnce() {
        StubJob.SessionState state = new StubJob.SessionState();

        assertTrue(state.claimFinishCallback());
        assertFalse(state.claimFinishCallback());
    }
}
