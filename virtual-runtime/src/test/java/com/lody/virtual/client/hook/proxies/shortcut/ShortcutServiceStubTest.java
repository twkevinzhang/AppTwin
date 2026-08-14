package com.lody.virtual.client.hook.proxies.shortcut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class ShortcutServiceStubTest {

    @Test
    public void bindsAndroid17PackageBearingMethodsWithExpectedProxyTypes() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();

        new ShortcutServiceStub(invocationStub);

        String[] shortcutReadMethods = {
                "getManifestShortcuts",
                "getDynamicShortcuts",
                "getShortcuts",
                "getPinnedShortcuts"
        };
        for (String method : shortcutReadMethods) {
            assertProxyType(invocationStub, method,
                    "ReplacePkgAndRepairShortcutListMethodProxy");
        }

        String[] callingPackageMethods = {
                "getShareTargets",
                "hasShareTargets",
                "disableShortcuts",
                "enableShortcuts",
                "removeDynamicShortcuts",
                "removeLongLivedShortcuts",
                "getRemainingCallCount",
                "getRateLimitResetTime",
                "getIconMaxDimensions",
                "getMaxShortcutCountPerActivity",
                "isRequestPinItemSupported",
                "reportShortcutUsed",
                "onApplicationActive",
                "removeAllDynamicShortcuts"
        };
        for (String method : callingPackageMethods) {
            assertProxyType(invocationStub, method, ReplaceCallingPkgMethodProxy.class);
        }

        String[] shortcutListMethods = {
                "setDynamicShortcuts",
                "addDynamicShortcuts",
                "updateShortcuts"
        };
        for (String method : shortcutListMethods) {
            assertProxyType(invocationStub, method, "ReplacePkgAndShortcutListMethodProxy");
        }

        String[] shortcutMethods = {
                "pushDynamicShortcut",
                "createShortcutResultIntent",
                "requestPinShortcut"
        };
        for (String method : shortcutMethods) {
            assertProxyType(invocationStub, method, "ReplacePkgAndShortcutMethodProxy");
        }
    }

    @Test
    public void doesNotBindAndroid17MethodsWithoutPackageArguments() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();

        new ShortcutServiceStub(invocationStub);

        assertNull(invocationStub.getMethodProxy("applyRestore"));
        assertNull(invocationStub.getMethodProxy("getBackupPayload"));
        assertNull(invocationStub.getMethodProxy("resetThrottling"));
    }

    @Test
    public void shortcutArgumentFindersIgnoreNullArgumentsWithoutLoadingAndroidStubs()
            throws Exception {
        assertNull(invokeShortcutFinder("ReplacePkgAndShortcutListMethodProxy",
                "findFirstShortcutList"));
        assertNull(invokeShortcutFinder("ReplacePkgAndShortcutMethodProxy",
                "findFirstShortcutInfo"));
    }

    @Test
    public void usesRenderedHostIconWithoutCreatingFallback() {
        Object renderedHostIcon = new Object();
        int[] fallbackCalls = {0};

        Object selected = ShortcutServiceStub.selectHostIcon(
                () -> renderedHostIcon,
                () -> {
                    fallbackCalls[0]++;
                    return new Object();
                });

        assertSame(renderedHostIcon, selected);
        assertEquals(0, fallbackCalls[0]);
    }

    @Test
    public void replacesUnrenderableHostIconWithBitmapFallback() {
        Object fallbackBitmapIcon = new Object();

        Object selected = ShortcutServiceStub.selectHostIcon(
                () -> {
                    throw new ClassCastException("VectorDrawable is not a BitmapDrawable");
                },
                () -> fallbackBitmapIcon);

        assertSame(fallbackBitmapIcon, selected);
    }

    @Test
    public void removesGuestIconWhenHostAndFallbackRenderingBothFail() {
        Object selected = ShortcutServiceStub.selectHostIcon(
                () -> {
                    throw new IllegalArgumentException("host icon");
                },
                () -> {
                    throw new IllegalStateException("fallback icon");
                });

        assertNull(selected);
    }

    @Test
    public void rewritesShortcutActivityToHostProxyComponent() {
        String proxyActivity = "com.lody.virtual.client.stub.ShortcutHandleActivity";
        String[] incomingShortcutActivity = {
                "jp.naver.line.android/jp.naver.line.android.activity.SplashActivity"
        };

        ShortcutServiceStub.rewriteShortcutActivity(
                incomingShortcutActivity,
                "org.apptwin",
                proxyActivity,
                (packageName, className) -> packageName + "/" + className,
                (shortcut, activity) -> shortcut[0] = activity);

        assertEquals("org.apptwin/" + proxyActivity, incomingShortcutActivity[0]);
    }

    @Test
    public void preservesGuestActionForShortcutProxyIntent() {
        assertEquals(
                "jp.naver.line.android.action.OPEN_CHAT",
                ShortcutServiceStub.selectShortcutProxyAction(
                        "jp.naver.line.android.action.OPEN_CHAT"));
    }

    @Test
    public void suppliesViewActionWhenGuestShortcutActionIsMissing() {
        assertEquals(
                "android.intent.action.VIEW",
                ShortcutServiceStub.selectShortcutProxyAction(null));
        assertEquals(
                "android.intent.action.VIEW",
                ShortcutServiceStub.selectShortcutProxyAction(""));
    }

    private static Object invokeShortcutFinder(String proxySimpleName, String finderName)
            throws Exception {
        Class<?> proxyClass = Class.forName(
                ShortcutServiceStub.class.getName() + "$" + proxySimpleName);
        Constructor<?> constructor = proxyClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object proxy = constructor.newInstance("test");
        Method finder = proxyClass.getDeclaredMethod(finderName, Object[].class);
        finder.setAccessible(true);
        return finder.invoke(proxy, (Object) new Object[]{null});
    }

    private static void assertProxyType(TestBinderInvocationStub invocationStub,
                                        String method,
                                        Class<? extends MethodProxy> expectedType) {
        MethodProxy proxy = invocationStub.getMethodProxy(method);
        assertNotNull(method, proxy);
        assertEquals(method, expectedType, proxy.getClass());
    }

    private static void assertProxyType(TestBinderInvocationStub invocationStub,
                                        String method,
                                        String expectedSimpleName) {
        MethodProxy proxy = invocationStub.getMethodProxy(method);
        assertNotNull(method, proxy);
        assertEquals(method, expectedSimpleName, proxy.getClass().getSimpleName());
    }

    /** Avoids Android's ServiceManager and Binder implementations in local JVM tests. */
    private static final class TestBinderInvocationStub extends BinderInvocationStub {
        private Map<String, MethodProxy> hooks;

        TestBinderInvocationStub() {
            super(new IInterface() {
                @Override
                public IBinder asBinder() {
                    return null;
                }
            });
            hooks = new HashMap<>();
        }

        @Override
        public MethodProxy addMethodProxy(MethodProxy methodProxy) {
            // BinderInvocationStub adds asBinder while this subclass is still being constructed.
            if (hooks != null) {
                hooks.put(methodProxy.getMethodName(), methodProxy);
            }
            return methodProxy;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <H extends MethodProxy> H getMethodProxy(String name) {
            return (H) hooks.get(name);
        }
    }
}
