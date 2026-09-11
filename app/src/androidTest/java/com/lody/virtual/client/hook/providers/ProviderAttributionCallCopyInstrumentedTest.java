package com.lody.virtual.client.hook.providers;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.lody.virtual.helper.utils.Reflect;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Local framework objects only: no provider calls, media, accounts, network or persisted data. */
@RunWith(AndroidJUnit4.class)
public class ProviderAttributionCallCopyInstrumentedTest {
    private static final String GUEST = "apptwin.fixture.guest";
    private static final int GUEST_UID = 10004;

    @Test
    public void realFrameworkCopyPreservesAllMetadataAndDelegatedPrincipal() {
        assumeTrue(Build.VERSION.SDK_INT >= 31);
        String host = "apptwin.fixture.host";
        Object next = source(9988, "apptwin.fixture.delegated", null);
        Object original = source(Process.myUid(), host, next);
        Object copy = copy(original, host, GUEST);

        assertNotSame(original, copy);
        assertNotSame(state(original), state(copy));
        assertEquals(host, value(original, "getPackageName"));
        assertEquals(GUEST, value(copy, "getPackageName"));
        assertEquals(Process.myUid(), ((Number) value(copy, "getUid")).intValue());
        assertSame(value(original, "getToken"), value(copy, "getToken"));
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals(value(original, "getPid"), value(copy, "getPid"));
        }
        assertEquals("fixture-tag", value(copy, "getAttributionTag"));
        assertEquals(value(original, "getRenouncedPermissions"),
                value(copy, "getRenouncedPermissions"));
        if (Build.VERSION.SDK_INT >= 35) {
            assertEquals(7, ((Number) value(copy, "getDeviceId")).intValue());
        }
        Object copiedNext = value(copy, "getNext");
        assertNotNull(copiedNext);
        assertSame(state(next), state(copiedNext));
        assertEquals("apptwin.fixture.delegated", value(copiedNext, "getPackageName"));
        assertEquals(9988, ((Number) value(copiedNext, "getUid")).intValue());
        assertSame(value(next, "getToken"), value(copiedNext, "getToken"));
    }

    @Test
    public void actualContextAttributionRemainsStableAcrossConcurrentCalls() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 31);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .createAttributionContext("apptwin-fixture-context");
        Object original = context.getAttributionSource();
        String host = (String) value(original, "getPackageName");
        Object originalToken = value(original, "getToken");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> internal = executor.submit(() -> {
                for (int i = 0; i < 30; i++) {
                    Object call = copy(original, host, GUEST);
                    assertNotSame(original, call);
                    assertEquals(GUEST, value(call, "getPackageName"));
                    assertEquals(host, value(context.getAttributionSource(), "getPackageName"));
                }
            });
            Future<?> external = executor.submit(() -> {
                for (int i = 0; i < 30; i++) {
                    Object call = copy(original, host, host);
                    assertEquals(host, value(call, "getPackageName"));
                    assertEquals(host, value(context.getAttributionSource(), "getPackageName"));
                }
            });
            internal.get(5, TimeUnit.SECONDS);
            external.get(5, TimeUnit.SECONDS);
            assertSame(original, context.getAttributionSource());
            assertSame(originalToken, value(context.getAttributionSource(), "getToken"));
            assertEquals(host, value(context, "getOpPackageName"));
            assertEquals("apptwin-fixture-context", context.getAttributionTag());
        } finally {
            executor.shutdownNow();
            assertTrue("fixture workers must finish", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void realFrameworkVirtualRootTranslationDoesNotChangeOriginal() {
        assumeTrue(Build.VERSION.SDK_INT >= 31);
        Object original = source(GUEST_UID, GUEST, null);
        Object copy = copy(original, "apptwin.fixture.host", "apptwin.fixture.host");
        assertEquals(Process.myUid(), ((Number) value(copy, "getUid")).intValue());
        assertEquals("apptwin.fixture.host", value(copy, "getPackageName"));
        assertEquals(GUEST_UID, ((Number) value(original, "getUid")).intValue());
        assertEquals(GUEST, value(original, "getPackageName"));
    }

    private static Object source(int uid, String pkg, Object next) {
        Reflect builder = Reflect.on("android.content.AttributionSource$Builder").create(uid)
                .call("setPackageName", pkg)
                .call("setAttributionTag", "fixture-tag")
                .call("setRenouncedPermissions", Collections.singleton("apptwin.fixture.permission"));
        if (Build.VERSION.SDK_INT >= 34) {
            builder.call("setPid", Process.myPid());
        }
        if (next != null) {
            builder.call("setNext", next);
        }
        if (Build.VERSION.SDK_INT >= 35) {
            builder.call("setDeviceId", 7);
        }
        Object source = builder.call("build").get();
        // A distinct local token tests preservation; it is never sent to a provider/service.
        Reflect.on(state(source)).set("token", new Binder());
        return source;
    }

    private static Object copy(Object source, String host, String target) {
        return ProviderAttributionCallCopy.copyForCall(source, GUEST, host,
                GUEST_UID, Process.myUid(), target);
    }

    private static Object state(Object source) {
        return Reflect.on(source).get("mAttributionSourceState");
    }

    private static Object value(Object source, String method) {
        return Reflect.on(source).call(method).get();
    }
}
