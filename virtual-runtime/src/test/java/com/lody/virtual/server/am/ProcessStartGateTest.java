package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ProcessStartGateTest {

    @Test
    public void contendedTryEnterReturnsWithoutWaitingForOwner() throws Exception {
        ProcessStartGate gate = new ProcessStartGate();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            gate.enter();
            try {
                ownerEntered.countDown();
                releaseOwner.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                gate.exit();
            }
        });

        owner.start();
        assertTrue(ownerEntered.await(1, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        assertFalse(gate.tryEnter());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue("tryEnter blocked for " + elapsedMillis + "ms", elapsedMillis < 100L);

        releaseOwner.countDown();
        owner.join(1_000L);
        assertFalse(owner.isAlive());
        assertTrue(gate.tryEnter());
        gate.exit();
    }
}
