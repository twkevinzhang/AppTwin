package com.lody.virtual.client.isolated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.FutureTask;

import org.junit.Test;

public class IsolatedWorkerCallGateTest {
    @Test
    public void returnsCompletedGuestCallbackResult() {
        FutureTask<String> task = new FutureTask<>(() -> "ready");
        task.run();

        assertEquals("ready", IsolatedWorkerCallGate.await(task));
    }

    @Test
    public void preservesRuntimeFailureFromGuestCallback() {
        IllegalArgumentException failure = new IllegalArgumentException("bad guest callback");
        FutureTask<String> task = new FutureTask<>(() -> {
            throw failure;
        });
        task.run();

        try {
            IsolatedWorkerCallGate.await(task);
            fail("expected callback failure");
        } catch (IllegalArgumentException actual) {
            assertSame(failure, actual);
        }
    }

    @Test
    public void interruptedWaitRestoresInterruptAndCancelsTask() {
        FutureTask<String> task = new FutureTask<>(() -> "unused");
        Thread.currentThread().interrupt();
        try {
            IsolatedWorkerCallGate.await(task);
            fail("expected interrupted wait");
        } catch (IllegalStateException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(task.isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void timedOutWaitCancelsWedgedGuestCallback() {
        FutureTask<String> task = new FutureTask<>(() -> "never scheduled");

        try {
            IsolatedWorkerCallGate.await(task, 10L);
            fail("expected callback timeout");
        } catch (IllegalStateException expected) {
            assertTrue(task.isCancelled());
        }
    }
}
