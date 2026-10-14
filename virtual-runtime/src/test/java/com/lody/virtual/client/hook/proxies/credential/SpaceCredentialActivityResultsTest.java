package com.lody.virtual.client.hook.proxies.credential;

import android.content.Intent;
import android.os.IBinder;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SpaceCredentialActivityResultsTest {
    private static IBinder token() {
        return (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return null;
                });
    }

    public static final class Result {
        public String mResultWho;
        public int mRequestCode;
        public int mResultCode = -1;
        public Intent mData;
        Result(int code) { mRequestCode = code; }
        Result(SpaceCredentialActivityResults.Registration registration) {
            mRequestCode = registration.requestCode;
            mResultWho = registration.resultWho;
        }
    }

    @Test public void exactOwnerReceivesResultOnceAndDuplicateIsDropped() {
        IBinder token = token();
        AtomicInteger calls = new AtomicInteger();
        SpaceCredentialActivityResults.Registration owner =
                SpaceCredentialActivityResults.register(token, (code, data) -> {
                    assertEquals(-1, code);
                    assertNull(data);
                    calls.incrementAndGet();
                });
        Result result = new Result(owner);
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, result));
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, result));
        assertEquals(1, calls.get());
    }

    @Test public void wrongTokenCodeAndWhoStayWithGuest() {
        IBinder token = token();
        SpaceCredentialActivityResults.Registration owner =
                SpaceCredentialActivityResults.register(token, (code, data) -> fail());
        assertFalse(SpaceCredentialActivityResults.consumeActivityResult(token(), new Result(owner.requestCode)));
        assertFalse(SpaceCredentialActivityResults.consumeActivityResult(token, new Result(-10)));
        Result child = new Result(owner.requestCode);
        child.mResultWho = "fragment";
        assertFalse(SpaceCredentialActivityResults.consumeActivityResult(token, child));
        assertFalse(SpaceCredentialActivityResults.consumeActivityResult(token, new Object()));
        owner.cancel();
    }

    @Test public void cancelledSessionDropsLateResultWithoutInvokingCallback() {
        IBinder token = token();
        SpaceCredentialActivityResults.Registration owner =
                SpaceCredentialActivityResults.register(token, (code, data) -> fail());
        owner.cancel();
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, new Result(owner)));
    }
    @Test public void requestCodeCollisionWithoutNamespaceRemainsGuestOwned() {
        IBinder token = token();
        AtomicInteger calls = new AtomicInteger();
        SpaceCredentialActivityResults.Registration owner =
                SpaceCredentialActivityResults.register(token, (code, data) -> calls.incrementAndGet());
        assertFalse(SpaceCredentialActivityResults.consumeActivityResult(token, new Result(owner.requestCode)));
        assertEquals(0, calls.get());
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, new Result(owner)));
        assertEquals(1, calls.get());
    }

    @Test public void reservedMarkerWithWrongTokenCodeOrSessionIsDropped() {
        IBinder token = token();
        SpaceCredentialActivityResults.Registration owner =
                SpaceCredentialActivityResults.register(token, (code, data) -> fail());
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token(), new Result(owner)));
        Result wrongCode = new Result(owner);
        wrongCode.mRequestCode = -1;
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, wrongCode));
        Result wrongWho = new Result(owner);
        wrongWho.mResultWho += "-stale";
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, wrongWho));
        owner.cancel();
    }

    @Test public void expiredTombstoneAndUnknownSessionMarkerStillNeverReachGuest() throws Exception {
        IBinder token = token();
        SpaceCredentialActivityResults.Registration owner =
                SpaceCredentialActivityResults.register(token, (code, data) -> fail());
        owner.cancel();
        java.lang.reflect.Field entryField = owner.getClass().getDeclaredField("entry");
        entryField.setAccessible(true);
        Object entry = entryField.get(owner);
        java.lang.reflect.Field expiresAt = entry.getClass().getDeclaredField("expiresAt");
        expiresAt.setAccessible(true);
        expiresAt.setLong(entry, 0L);
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, new Result(owner)));
        Result unknown = new Result(-1);
        unknown.mResultWho = "apptwin:space-credential:unknown-session";
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(token, unknown));
        assertTrue(SpaceCredentialActivityResults.consumeActivityResult(null, unknown));
    }
}
