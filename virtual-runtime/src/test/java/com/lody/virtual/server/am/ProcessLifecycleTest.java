package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ProcessLifecycleTest {

    @Test
    public void startingWorkWaitsAndReadyDrainsInFifoOrder() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(7);
        List<String> delivered = new ArrayList<>();

        assertTrue(lifecycle.enqueue(operation(7, PendingServiceOperation.Type.START_ARGS,
                "start", delivered)));
        assertTrue(lifecycle.enqueue(operation(7, PendingServiceOperation.Type.BIND,
                "bind", delivered)));
        assertEquals(0, lifecycle.drain(7));
        assertTrue(delivered.isEmpty());

        assertTrue(lifecycle.markReady(7));
        assertEquals(2, lifecycle.drain(7));
        assertEquals(Arrays.asList("start", "bind"), delivered);
        assertEquals(0, lifecycle.pendingCount());
        assertEquals(ProcessLifecycle.State.READY, lifecycle.state());
    }

    @Test
    public void readyEnqueueStillRequiresExplicitDrain() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(3);
        List<String> delivered = new ArrayList<>();
        assertTrue(lifecycle.markReady(3));

        assertTrue(lifecycle.enqueue(operation(3, PendingServiceOperation.Type.CONNECT,
                "connect", delivered)));

        assertTrue(delivered.isEmpty());
        assertEquals(1, lifecycle.drain(3));
        assertEquals(Collections.singletonList("connect"), delivered);
    }

    @Test
    public void staleGenerationCannotReadyOrEnqueueWork() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(11);
        List<ProcessLifecycle.TerminalReason> cancellations = new ArrayList<>();
        PendingServiceOperation stale = cancellableOperation(10, "stale", cancellations);

        assertFalse(lifecycle.markReady(10));
        assertFalse(lifecycle.enqueue(stale));

        assertEquals(ProcessLifecycle.State.STARTING, lifecycle.state());
        assertEquals(0, lifecycle.pendingCount());
        assertTrue(stale.isCancelled());
        assertEquals(Collections.singletonList(ProcessLifecycle.TerminalReason.STALE_GENERATION),
                cancellations);
    }

    @Test
    public void terminalTransitionClearsAndCancelsEveryPendingOperation() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(5);
        List<ProcessLifecycle.TerminalReason> cancellations = new ArrayList<>();
        PendingServiceOperation first = cancellableOperation(5, "first", cancellations);
        PendingServiceOperation second = cancellableOperation(5, "second", cancellations);
        lifecycle.enqueue(first);
        lifecycle.enqueue(second);

        assertTrue(lifecycle.markFailed(5, ProcessLifecycle.TerminalReason.STARTUP_TIMEOUT));

        assertEquals(ProcessLifecycle.State.FAILED, lifecycle.state());
        assertEquals(ProcessLifecycle.TerminalReason.STARTUP_TIMEOUT, lifecycle.terminalReason());
        assertEquals(0, lifecycle.pendingCount());
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertEquals(Arrays.asList(ProcessLifecycle.TerminalReason.STARTUP_TIMEOUT,
                ProcessLifecycle.TerminalReason.STARTUP_TIMEOUT), cancellations);
        assertFalse(lifecycle.markReady(5));
        assertEquals(0, lifecycle.drain(5));
    }

    @Test
    public void staleStartupWatchdogCannotFailReadyGeneration() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(21);
        List<String> delivered = new ArrayList<>();
        assertTrue(lifecycle.markReady(21));
        assertTrue(lifecycle.enqueue(operation(21,
                PendingServiceOperation.Type.START_ARGS, "ready", delivered)));

        assertFalse(lifecycle.markStartupTimedOut(21));
        assertEquals(ProcessLifecycle.State.READY, lifecycle.state());
        assertNull(lifecycle.terminalReason());
        assertEquals(1, lifecycle.drain(21));
        assertEquals(Collections.singletonList("ready"), delivered);
    }

    @Test
    public void startupWatchdogFailsStartingGenerationAndCancelsPendingWork() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(22);
        List<ProcessLifecycle.TerminalReason> cancellations = new ArrayList<>();
        PendingServiceOperation pending = cancellableOperation(22, "pending", cancellations);
        assertTrue(lifecycle.enqueue(pending));

        assertTrue(lifecycle.markStartupTimedOut(22));

        assertEquals(ProcessLifecycle.State.FAILED, lifecycle.state());
        assertEquals(ProcessLifecycle.TerminalReason.STARTUP_TIMEOUT,
                lifecycle.terminalReason());
        assertEquals(0, lifecycle.pendingCount());
        assertTrue(pending.isCancelled());
        assertEquals(Collections.singletonList(ProcessLifecycle.TerminalReason.STARTUP_TIMEOUT),
                cancellations);
    }

    @Test
    public void unbindBeforeReadyMakesQueuedBindActionAValidatedNoOp() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(23);
        ServiceRecord.IntentBindRecord binding = new ServiceRecord.IntentBindRecord();
        assertTrue(binding.requestBindIfNeeded());
        List<String> delivered = new ArrayList<>();
        assertTrue(lifecycle.enqueue(new PendingServiceOperation(23,
                PendingServiceOperation.Type.BIND,
                () -> {
                    if (binding.shouldDispatchBind()) {
                        delivered.add("bind");
                    }
                })));

        assertTrue(binding.cancelPendingBindIfNoConnections());
        assertTrue(lifecycle.markReady(23));

        assertEquals(1, lifecycle.drain(23));
        assertTrue(delivered.isEmpty());
    }

    @Test
    public void deadMayFollowFailedAndRejectsNewWorkWithTerminalReason() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(4);
        lifecycle.markFailed(4, ProcessLifecycle.TerminalReason.APPLICATION_BIND_FAILED);
        assertTrue(lifecycle.markDead(4, ProcessLifecycle.TerminalReason.PROCESS_DIED));
        List<ProcessLifecycle.TerminalReason> cancellations = new ArrayList<>();
        PendingServiceOperation operation = cancellableOperation(4, "late", cancellations);

        assertFalse(lifecycle.enqueue(operation));

        assertEquals(ProcessLifecycle.State.DEAD, lifecycle.state());
        assertEquals(Collections.singletonList(ProcessLifecycle.TerminalReason.PROCESS_DIED),
                cancellations);
        assertFalse(lifecycle.markDead(4, ProcessLifecycle.TerminalReason.PROCESS_DIED));
    }

    @Test
    public void dispatchFailureFailsGenerationAndCancelsRemainingWork() {
        ProcessLifecycle lifecycle = new ProcessLifecycle(8);
        List<String> delivered = new ArrayList<>();
        List<ProcessLifecycle.TerminalReason> cancellations = new ArrayList<>();
        lifecycle.enqueue(operation(8, PendingServiceOperation.Type.START_ARGS,
                "first", delivered));
        lifecycle.enqueue(new PendingServiceOperation(8, PendingServiceOperation.Type.BIND,
                () -> { throw new Exception("binder died"); }));
        PendingServiceOperation tail = cancellableOperation(8, "tail", cancellations);
        lifecycle.enqueue(tail);
        lifecycle.markReady(8);

        assertEquals(1, lifecycle.drain(8));

        assertEquals(Collections.singletonList("first"), delivered);
        assertEquals(ProcessLifecycle.State.FAILED, lifecycle.state());
        assertEquals(ProcessLifecycle.TerminalReason.DISPATCH_FAILED, lifecycle.terminalReason());
        assertEquals("BIND", lifecycle.failedOperation());
        assertEquals("binder died", lifecycle.dispatchFailure().getMessage());
        assertTrue(tail.isCancelled());
        assertEquals(Collections.singletonList(ProcessLifecycle.TerminalReason.DISPATCH_FAILED),
                cancellations);
    }

    @Test
    public void onlyOneDrainerRunsAndItConsumesWorkAddedWhileRunning() throws Exception {
        ProcessLifecycle lifecycle = new ProcessLifecycle(12);
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch enteredFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        lifecycle.enqueue(new PendingServiceOperation(12, PendingServiceOperation.Type.START_ARGS,
                () -> {
                    enteredFirst.countDown();
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                    delivered.add("first");
                }));
        lifecycle.markReady(12);

        Thread firstDrainer = new Thread(() -> lifecycle.drain(12));
        firstDrainer.start();
        assertTrue(enteredFirst.await(5, TimeUnit.SECONDS));
        assertTrue(lifecycle.isDraining());

        lifecycle.enqueue(operation(12, PendingServiceOperation.Type.BIND, "second", delivered));
        assertEquals(0, lifecycle.drain(12));
        releaseFirst.countDown();
        firstDrainer.join(5_000);

        assertFalse(firstDrainer.isAlive());
        assertEquals(Arrays.asList("first", "second"), delivered);
        assertFalse(lifecycle.isDraining());
        assertEquals(0, lifecycle.pendingCount());
    }

    @Test
    public void createServiceCannotBeRepresentedAsPendingWork() {
        try {
            PendingServiceOperation.Type.valueOf("CREATE_SERVICE");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("CREATE_SERVICE must bypass the readiness queue");
    }

    private static PendingServiceOperation operation(long generation,
                                                     PendingServiceOperation.Type type,
                                                     String value,
                                                     List<String> delivered) {
        return new PendingServiceOperation(generation, type, () -> delivered.add(value));
    }

    private static PendingServiceOperation cancellableOperation(
            long generation, String value,
            List<ProcessLifecycle.TerminalReason> cancellations) {
        return new PendingServiceOperation(generation, PendingServiceOperation.Type.STOP, value,
                () -> { }, cancellations::add);
    }

}
