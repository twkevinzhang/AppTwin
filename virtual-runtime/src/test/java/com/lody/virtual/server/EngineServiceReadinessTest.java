package com.lody.virtual.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class EngineServiceReadinessTest {
    @Test public void neverPublishesPartialServices() {
        EngineServiceReadiness readiness = new EngineServiceReadiness();
        assertFalse(readiness.await(0));
        readiness.complete(true);
        assertTrue(readiness.await(0));
    }
    @Test public void failedInitializationStaysUnavailable() {
        EngineServiceReadiness readiness = new EngineServiceReadiness();
        readiness.complete(false);
        assertFalse(readiness.await(0));
    }
    @Test public void timeoutDoesNotRevokeLaterSuccessfulInitialization() {
        EngineServiceReadiness readiness = new EngineServiceReadiness();
        assertFalse(readiness.await(1));
        readiness.complete(true);
        assertTrue(readiness.await(0));
    }
    @Test public void completionWakesWaitingBinderClient() throws Exception {
        EngineServiceReadiness readiness = new EngineServiceReadiness();
        java.util.concurrent.FutureTask<Boolean> result =
                new java.util.concurrent.FutureTask<>(() -> readiness.await(1000));
        Thread client = new Thread(result);
        client.start();
        readiness.complete(true);
        assertTrue(result.get(1, java.util.concurrent.TimeUnit.SECONDS));
        client.join(1000);
        assertFalse(client.isAlive());
    }
    @Test public void interruptionPreservesInterruptAndDoesNotPublish() {
        EngineServiceReadiness readiness = new EngineServiceReadiness();
        Thread.currentThread().interrupt();
        try {
            assertFalse(readiness.await(1000));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
