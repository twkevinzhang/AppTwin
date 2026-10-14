package com.lody.virtual.client.hook.proxies.am;

import android.os.IBinder;

import com.lody.virtual.client.interfaces.IInjector;
import com.lody.virtual.client.ipc.VActivityManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Android 12 moved Activity caller queries out of IActivityTaskManager. */
public final class ActivityClientControllerStub implements IInjector {
    private Object proxy;

    @Override
    public synchronized void inject() throws Throwable {
        Class<?> client = Class.forName("android.app.ActivityClient");
        Method getter = client.getDeclaredMethod("getActivityClientController");
        getter.setAccessible(true);
        Object current = getter.invoke(null);
        if (current == null) {
            throw new IllegalStateException("Missing activity client controller");
        }
        if (current != proxy) {
            Class<?> controller = Class.forName("android.app.IActivityClientController");
            proxy = Proxy.newProxyInstance(controller.getClassLoader(), new Class<?>[]{controller},
                    new CallerHandler(current, (name, token) -> "getCallingActivity".equals(name)
                            ? VActivityManager.get().getCallingActivity(token)
                            : VActivityManager.get().getCallingPackage(token)));
        }
        Field singletonField = client.getDeclaredField("INTERFACE_SINGLETON");
        singletonField.setAccessible(true);
        Object singleton = singletonField.get(null);
        // mKnownInstance takes precedence over Singleton#get() after launch transactions.
        Field known = findField(singleton.getClass(), "mKnownInstance");
        Field instance = findField(singleton.getClass(), "mInstance");
        synchronized (singleton) {
            instance.set(singleton, proxy);
            known.set(singleton, proxy);
        }
    }

    @Override
    public synchronized boolean isEnvBad() {
        try {
            Method getter = Class.forName("android.app.ActivityClient")
                    .getDeclaredMethod("getActivityClientController");
            getter.setAccessible(true);
            return proxy == null || getter.invoke(null) != proxy;
        } catch (ReflectiveOperationException failure) {
            return true;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                // android.util.Singleton owns mInstance, the framework subclass owns mKnownInstance.
            }
        }
        throw new NoSuchFieldException(name);
    }

    interface CallerLookup {
        Object get(String method, IBinder token);
    }

    static final class CallerHandler implements InvocationHandler {
        private final Object base;
        private final CallerLookup lookup;

        CallerHandler(Object base, CallerLookup lookup) {
            this.base = base;
            this.lookup = lookup;
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (("getCallingActivity".equals(name) || "getCallingPackage".equals(name))
                    && args != null && args.length == 1) {
                if (!(args[0] instanceof IBinder)) return null;
                return lookup.get(name, (IBinder) args[0]);
            }
            try {
                return method.invoke(base, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
