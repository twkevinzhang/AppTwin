package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class LinePushBroadcastAttestationRegistryTest {
    private static final String GMS = "com.google.android.gms";
    private static final String LINE = "jp.naver.line.android";
    private static final String C2DM = "com.google.android.c2dm.intent.RECEIVE";
    private static final int VUID = 100_001;

    @Test
    public void issuanceRequiresExactReadyGmsCallerUserActionAndTarget() {
        assertTrue(canIssue(GMS, VUID, 1, true, true, true, C2DM, LINE, true));
        assertFalse(canIssue(LINE, VUID, 1, true, true, true, C2DM, LINE, true));
        assertFalse(canIssue(GMS, 1, 0, true, true, true, C2DM, LINE, true));
        assertFalse(canIssue(GMS, VUID, 2, true, true, true, C2DM, LINE, true));
        assertFalse(canIssue(GMS, VUID, 1, false, true, true, C2DM, LINE, true));
        assertFalse(canIssue(GMS, VUID, 1, true, false, true, C2DM, LINE, true));
        assertFalse(canIssue(GMS, VUID, 1, true, true, false, C2DM, LINE, true));
        assertFalse(canIssue(GMS, VUID, 1, true, true, true,
                "android.intent.action.SCREEN_ON", LINE, true));
        assertFalse(canIssue(GMS, VUID, 1, true, true, true,
                C2DM, "com.example.other", true));
        assertFalse(canIssue(GMS, VUID, 1, true, true, true, C2DM, LINE, false));
    }

    @Test
    public void tokenIsBoundAndConsumedExactlyOnceEvenOnWrongPresentation() {
        FakeClock clock = new FakeClock();
        LinePushBroadcastAttestationRegistry registry = registry(clock);
        LinePushBroadcastAttestationRegistry.Binding exact = binding(VUID, 1, C2DM, LINE);

        String replay = registry.issue(exact);
        assertTrue(registry.consume(replay, exact));
        assertFalse(registry.consume(replay, exact));

        assertConsumedOnMismatch(registry, exact, binding(200_001, 1, C2DM, LINE));
        assertConsumedOnMismatch(registry, exact, binding(VUID, 2, C2DM, LINE));
        assertConsumedOnMismatch(registry, exact,
                binding(VUID, 1, "android.intent.action.SCREEN_ON", LINE));
        assertConsumedOnMismatch(registry, exact,
                binding(VUID, 1, C2DM, "com.example.other"));

        LinePushStopFence fence = new LinePushStopFence();
        LinePushStopFence.Permit beforeStop = fence.acquire(LINE, 1);
        String crossedStop = registry.issue(binding(VUID, 1, C2DM, LINE, beforeStop));
        LinePushStopFence.StopScope stop = fence.begin(LINE, 1);
        fence.end(stop);
        LinePushStopFence.Permit afterStop = fence.acquire(LINE, 1);
        assertFalse(registry.consume(
                crossedStop, binding(VUID, 1, C2DM, LINE, afterStop)));
    }

    @Test
    public void expiryFailsClosedAndCleansCapacity() {
        FakeClock clock = new FakeClock();
        LinePushBroadcastAttestationRegistry registry = registry(clock);
        LinePushBroadcastAttestationRegistry.Binding exact = binding(VUID, 1, C2DM, LINE);
        String expired = registry.issue(exact);

        clock.now = LinePushBroadcastAttestationRegistry.TTL_MILLIS;
        assertFalse(registry.consume(expired, exact));
        assertEquals(0, registry.pendingCount());

        for (int index = 0; index < LinePushBroadcastAttestationRegistry.MAX_PENDING; index++) {
            assertNotNull(registry.issue(exact));
        }
        assertNull(registry.issue(exact));
        clock.now += LinePushBroadcastAttestationRegistry.TTL_MILLIS;
        assertNotNull(registry.issue(exact));
        assertEquals(1, registry.pendingCount());
    }

    private static boolean canIssue(String callerPackage, int callerVuid, int callerUserId,
            boolean ready, boolean current, boolean active,
            String action, String target, boolean implicit) {
        return LinePushBroadcastAttestationRegistry.canIssue(
                callerPackage, callerVuid, callerUserId, ready, current, active,
                action, target, implicit);
    }

    private static void assertConsumedOnMismatch(LinePushBroadcastAttestationRegistry registry,
            LinePushBroadcastAttestationRegistry.Binding issued,
            LinePushBroadcastAttestationRegistry.Binding presented) {
        String token = registry.issue(issued);
        assertFalse(registry.consume(token, presented));
        assertFalse(registry.consume(token, issued));
    }

    private static LinePushBroadcastAttestationRegistry registry(FakeClock clock) {
        AtomicInteger sequence = new AtomicInteger();
        return new LinePushBroadcastAttestationRegistry(
                clock, () -> "token-" + sequence.incrementAndGet());
    }

    private static LinePushBroadcastAttestationRegistry.Binding binding(
            int senderVuid, int senderUserId, String action, String target) {
        return binding(senderVuid, senderUserId, action, target,
                new LinePushStopFence().acquire(LINE, senderUserId));
    }

    private static LinePushBroadcastAttestationRegistry.Binding binding(
            int senderVuid, int senderUserId, String action, String target,
            LinePushStopFence.Permit stopPermit) {
        return new LinePushBroadcastAttestationRegistry.Binding(
                senderVuid, senderUserId, action, target, stopPermit);
    }

    private static final class FakeClock
            implements LinePushBroadcastAttestationRegistry.Clock {
        long now;

        @Override public long now() {
            return now;
        }
    }
}
