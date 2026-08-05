package com.lody.virtual.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class StubProcessOwnerTest {

    private final Set<Object> liveTokens =
            Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private final StubProcessOwner owner = new StubProcessOwner(new StubProcessOwner.TokenLiveness() {
        @Override
        public boolean isAlive(Object token) {
            return liveTokens.contains(token);
        }
    });

    @Test
    public void emptySlotAcceptsAndExactRetryIsIdempotent() {
        Object token = new Object();
        liveTokens.add(token);

        StubProcessOwner.ClaimResult first = owner.claim(100001, "jp.naver.line.android",
                "jp.naver.line.android", 7, token);
        StubProcessOwner.ClaimResult retry = owner.claim(100001, "jp.naver.line.android",
                "jp.naver.line.android", 7, token);

        assertTrue(first.isAccepted());
        assertEquals(StubProcessOwner.REASON_ACCEPTED, first.getReason());
        assertTrue(retry.isAccepted());
        assertEquals(StubProcessOwner.REASON_IDEMPOTENT, retry.getReason());
        assertSame(token, retry.getCurrentIdentity().getServerToken());
    }

    @Test
    public void liveOwnerRejectsDifferentServerToken() {
        Object oldToken = new Object();
        Object newToken = new Object();
        liveTokens.add(oldToken);
        owner.claim(100001, "jp.naver.line.android", "jp.naver.line.android", 7, oldToken);

        StubProcessOwner.ClaimResult result = owner.claim(100001, "jp.naver.line.android",
                "jp.naver.line.android", 7, newToken);

        assertFalse(result.isAccepted());
        assertEquals(StubProcessOwner.REASON_TOKEN_STILL_ALIVE, result.getReason());
        assertSame(oldToken, result.getCurrentIdentity().getServerToken());
    }

    @Test
    public void deadOwnerCanReattachBeforeGuestBinding() {
        Object oldToken = new Object();
        Object newToken = new Object();
        liveTokens.add(oldToken);
        owner.claim(100001, "jp.naver.line.android", "jp.naver.line.android", 7, oldToken);
        liveTokens.remove(oldToken);
        liveTokens.add(newToken);

        StubProcessOwner.ClaimResult result = owner.claim(100001, "jp.naver.line.android",
                "jp.naver.line.android", 8, newToken);

        assertTrue(result.isAccepted());
        assertEquals(StubProcessOwner.REASON_REATTACHED, result.getReason());
        assertEquals(8, result.getCurrentIdentity().getGeneration());
        assertSame(newToken, result.getCurrentIdentity().getServerToken());
    }

    @Test
    public void guestBindingMakesOwnerImmutableEvenAfterTokenDeath() {
        Object oldToken = new Object();
        Object newToken = new Object();
        liveTokens.add(oldToken);
        owner.claim(100001, "jp.naver.line.android", "jp.naver.line.android", 7, oldToken);
        owner.markGuestBound();
        liveTokens.remove(oldToken);

        StubProcessOwner.ClaimResult result = owner.claim(100001, "jp.naver.line.android",
                "jp.naver.line.android", 8, newToken);

        assertFalse(result.isAccepted());
        assertEquals(StubProcessOwner.REASON_GUEST_ALREADY_BOUND, result.getReason());
        assertSame(oldToken, result.getCurrentIdentity().getServerToken());
    }

    @Test
    public void differentLogicalIdentityNeverOverwritesSlot() {
        Object oldToken = new Object();
        Object newToken = new Object();
        owner.claim(100001, "jp.naver.line.android", "jp.naver.line.android", 7, oldToken);

        StubProcessOwner.ClaimResult result = owner.claim(200001, "jp.naver.line.android",
                "jp.naver.line.android", 8, newToken);

        assertFalse(result.isAccepted());
        assertEquals(StubProcessOwner.REASON_IDENTITY_MISMATCH, result.getReason());
        assertSame(oldToken, result.getCurrentIdentity().getServerToken());
    }

    @Test
    public void concurrentDifferentClaimsHaveSingleWinner() throws Exception {
        final StubProcessOwner concurrentOwner = new StubProcessOwner(
                new StubProcessOwner.TokenLiveness() {
                    @Override
                    public boolean isAlive(Object token) {
                        return true;
                    }
                });
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger accepted = new AtomicInteger();
        Thread first = claimInThread(concurrentOwner, ready, start, accepted, 100001, "one");
        Thread second = claimInThread(concurrentOwner, ready, start, accepted, 200001, "two");

        first.start();
        second.start();
        ready.await();
        start.countDown();
        first.join();
        second.join();

        assertEquals(1, accepted.get());
    }

    private static Thread claimInThread(final StubProcessOwner owner, final CountDownLatch ready,
                                        final CountDownLatch start, final AtomicInteger accepted,
                                        final int vuid, final String packageName) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (owner.claim(vuid, packageName, packageName, 1, new Object()).isAccepted()) {
                    accepted.incrementAndGet();
                }
            }
        });
    }
}
