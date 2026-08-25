package com.lody.virtual.server.am;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmsReconciliationReliabilityTest {
    @Test
    public void failedLatestReconcileRemainsUnreliable() {
        GmsReconciliationReliability reliability =
                new GmsReconciliationReliability(new Object());

        long generation = reliability.begin();
        reliability.finish(generation, false);

        assertFalse(reliability.isComplete());
    }

    @Test
    public void staleSuccessCannotOverrideNewerFailedReconcile() {
        GmsReconciliationReliability reliability =
                new GmsReconciliationReliability(new Object());
        long staleSuccess = reliability.begin();
        long newerFailure = reliability.begin();

        reliability.finish(newerFailure, false);
        reliability.finish(staleSuccess, true);

        assertFalse(reliability.isComplete());
    }

    @Test
    public void latestSuccessfulReconcileBecomesReliable() {
        GmsReconciliationReliability reliability =
                new GmsReconciliationReliability(new Object());
        long generation = reliability.begin();

        reliability.finish(generation, true);

        assertTrue(reliability.isComplete());
    }
}
