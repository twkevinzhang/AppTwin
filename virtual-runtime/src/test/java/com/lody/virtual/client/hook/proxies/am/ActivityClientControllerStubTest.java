package com.lody.virtual.client.hook.proxies.am;

import android.os.IBinder;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ActivityClientControllerStubTest {
    public interface Controller {
        Object getCallingActivity(IBinder token);
        String getCallingPackage(IBinder token);
        String unrelated(String value);
        void fails();
    }

    private final AtomicInteger baseCalls = new AtomicInteger();
    private final Controller base = new Controller() {
        public Object getCallingActivity(IBinder token) { baseCalls.incrementAndGet(); return "host"; }
        public String getCallingPackage(IBinder token) { baseCalls.incrementAndGet(); return "host"; }
        public String unrelated(String value) { baseCalls.incrementAndGet(); return value; }
        public void fails() { throw new SecurityException("original"); }
    };

    private Controller proxy(ActivityClientControllerStub.CallerLookup lookup) {
        return (Controller) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Controller.class}, new ActivityClientControllerStub.CallerHandler(base, lookup));
    }

    private IBinder token() {
        return (IBinder) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, args) -> null);
    }

    @Test public void bothCallerMethodsUseExactTokenAndNeverPhysicalController() {
        IBinder token = token();
        AtomicInteger queries = new AtomicInteger();
        Controller controller = proxy((name, supplied) -> {
            assertSame(token, supplied);
            queries.incrementAndGet();
            return name.equals("getCallingPackage") ? "guest" : "guest-component";
        });
        assertEquals("guest", controller.getCallingPackage(token));
        assertEquals("guest-component", controller.getCallingActivity(token));
        assertEquals(2, queries.get());
        assertEquals(0, baseCalls.get());
    }

    @Test public void missingVirtualCallerDoesNotFallBackToHost() {
        Controller controller = proxy((name, token) -> null);
        assertNull(controller.getCallingPackage(token()));
        assertNull(controller.getCallingActivity(token()));
        assertEquals(0, baseCalls.get());
    }

    @Test public void nullTokenCannotQueryOrFallBackToHost() {
        Controller controller = proxy((name, token) -> { throw new AssertionError("lookup"); });
        assertNull(controller.getCallingPackage(null));
        assertNull(controller.getCallingActivity(null));
        assertEquals(0, baseCalls.get());
    }

    @Test public void unrelatedMethodsKeepOriginalArgumentsAndResult() {
        Controller controller = proxy((name, token) -> { throw new AssertionError("lookup"); });
        String original = new String("unchanged");
        assertSame(original, controller.unrelated(original));
        assertEquals(1, baseCalls.get());
    }

    @Test public void delegatedExceptionIsNotWrapped() {
        try {
            proxy((name, token) -> null).fails();
            fail("expected original exception");
        } catch (SecurityException original) {
            assertEquals("original", original.getMessage());
        }
    }
}
