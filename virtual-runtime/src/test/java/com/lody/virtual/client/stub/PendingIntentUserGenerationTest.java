package com.lody.virtual.client.stub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PendingIntentUserGenerationTest {

    @Test
    public void resetGenerationChangesHostPendingIntentFilterIdentity() {
        String beforeReset = PendingIntentUserGeneration.identityCategory(
                "com.google.android.gms", 3, 41, 51L, 61L);
        String afterReset = PendingIntentUserGeneration.identityCategory(
                "com.google.android.gms", 3, 41, 51L, 62L);

        assertFalse(beforeReset.equals(afterReset));
    }
    @Test
    public void processDeathThenDeleteAndReuseCannotDispatchOldToken() {
        int deletedGroupSerial = 41;
        int reusedNumericUserSerial = 42;

        assertFalse(PendingIntentUserGeneration.matches(
                deletedGroupSerial, 100L, 300L, reusedNumericUserSerial, 200L, 300L));
        assertTrue(PendingIntentUserGeneration.matches(
                reusedNumericUserSerial, 200L, 300L,
                reusedNumericUserSerial, 200L, 300L));
        assertFalse(PendingIntentUserGeneration.matches(
                -1, 200L, 300L, reusedNumericUserSerial, 200L, 300L));
    }

    @Test
    public void epochBumpMakesCrashSurvivingTokenInertWithoutAffectingAnotherUser() {
        assertFalse(PendingIntentUserGeneration.matches(7, 10L, 30L, 7, 11L, 30L));
        assertFalse(PendingIntentUserGeneration.matches(7, 11L, 30L, 7, 11L, 31L));
        assertTrue(PendingIntentUserGeneration.matches(7, 11L, 31L, 7, 11L, 31L));
        assertTrue(PendingIntentUserGeneration.matches(8, 90L, 80L, 8, 90L, 80L));
    }
}
