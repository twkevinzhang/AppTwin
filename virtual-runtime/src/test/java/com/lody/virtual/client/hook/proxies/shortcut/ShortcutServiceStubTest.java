package com.lody.virtual.client.hook.proxies.shortcut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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

    @Test
    public void recognizesOnlyHostOwnedShortcutsWithDirectHostTargets() {
        assertTrue(ShortcutServiceStub.isHostNativeShortcutOwnership(
                "host.package", "host.package", true, false));
        assertTrue(ShortcutServiceStub.isHostNativeShortcutOwnership(
                "host.package", "host.package", false, true));

        assertFalse(ShortcutServiceStub.isHostNativeShortcutOwnership(
                "guest.package", "host.package", true, true));
        assertFalse(ShortcutServiceStub.isHostNativeShortcutOwnership(
                "host.package", "host.package", false, false));
    }

    @Test
    public void guestReadFiltersHostNativeShortcutWithoutChangingItsIconOrIntent() {
        TestShortcut hostNative = new TestShortcut(
                "host.package", true, "product-icon", "host/MainActivity");
        TestShortcut rewrittenGuest = new TestShortcut(
                "host.package", false, "guest-icon", "host/ShortcutHandleActivity");

        List<TestShortcut> visible = ShortcutServiceStub.filterAndTransformGuestShortcuts(
                Arrays.asList(hostNative, rewrittenGuest),
                shortcut -> ShortcutServiceStub.isHostNativeShortcutOwnership(
                        shortcut.owner, "host.package", false, shortcut.directHostTarget),
                shortcut -> shortcut.repairs++);

        assertEquals(1, visible.size());
        assertSame(rewrittenGuest, visible.get(0));
        assertEquals("product-icon", hostNative.icon);
        assertEquals("host/MainActivity", hostNative.intent);
        assertEquals(0, hostNative.repairs);
        assertEquals(1, rewrittenGuest.repairs);
    }

    @Test
    public void guestReadWriteRoundTripPreservesRewrittenShortcutIdentityAndPayload() {
        TestShortcut rewrittenGuest = new TestShortcut(
                "host.package", false, "bitmap-icon", "host/ShortcutHandleActivity?guest=1");

        List<TestShortcut> read = ShortcutServiceStub.filterAndTransformGuestShortcuts(
                Arrays.asList(rewrittenGuest),
                shortcut -> ShortcutServiceStub.isHostNativeShortcutOwnership(
                        shortcut.owner, "host.package", false, shortcut.directHostTarget),
                shortcut -> shortcut.repairs++);
        List<TestShortcut> write = ShortcutServiceStub.filterAndTransformGuestShortcuts(
                read,
                shortcut -> ShortcutServiceStub.isHostNativeShortcutOwnership(
                        shortcut.owner, "host.package", false, shortcut.directHostTarget),
                shortcut -> shortcut.writes++);

        assertEquals(1, write.size());
        assertSame(rewrittenGuest, write.get(0));
        assertEquals("bitmap-icon", rewrittenGuest.icon);
        assertEquals("host/ShortcutHandleActivity?guest=1", rewrittenGuest.intent);
        assertEquals(1, rewrittenGuest.repairs);
        assertEquals(1, rewrittenGuest.writes);
    }

    @Test
    public void guestListWriteDropsHostNativeAndStillRewritesGenuineGuestShortcut() {
        TestShortcut hostNative = new TestShortcut(
                "host.package", true, "product-icon", "host/MainActivity");
        TestShortcut genuineGuest = new TestShortcut(
                "guest.package", false, "guest-resource-icon", "guest/LaunchActivity");

        List<TestShortcut> writes = ShortcutServiceStub.filterAndTransformGuestShortcuts(
                Arrays.asList(hostNative, genuineGuest),
                shortcut -> ShortcutServiceStub.isHostNativeShortcutOwnership(
                        shortcut.owner, "host.package", false, shortcut.directHostTarget),
                shortcut -> {
                    shortcut.owner = "host.package";
                    shortcut.icon = "host-bitmap-icon";
                    shortcut.intent = "host/ShortcutHandleActivity";
                    shortcut.writes++;
                });

        assertEquals(1, writes.size());
        assertSame(genuineGuest, writes.get(0));
        assertEquals("host.package", genuineGuest.owner);
        assertEquals("host-bitmap-icon", genuineGuest.icon);
        assertEquals("host/ShortcutHandleActivity", genuineGuest.intent);
        assertEquals(1, genuineGuest.writes);
        assertEquals("product-icon", hostNative.icon);
        assertEquals("host/MainActivity", hostNative.intent);
        assertEquals(0, hostNative.writes);
    }

    @Test
    public void guestSingleWriteProtectsHostNativeAndRewritesGenuineGuestShortcut() {
        TestShortcut hostNative = new TestShortcut(
                "host.package", true, "product-icon", "host/MainActivity");
        TestShortcut genuineGuest = new TestShortcut(
                "guest.package", false, "guest-icon", "guest/LaunchActivity");

        assertFalse(ShortcutServiceStub.transformGuestShortcut(
                hostNative,
                shortcut -> ShortcutServiceStub.isHostNativeShortcutOwnership(
                        shortcut.owner, "host.package", false, shortcut.directHostTarget),
                shortcut -> shortcut.writes++));
        assertTrue(ShortcutServiceStub.transformGuestShortcut(
                genuineGuest,
                shortcut -> ShortcutServiceStub.isHostNativeShortcutOwnership(
                        shortcut.owner, "host.package", false, shortcut.directHostTarget),
                shortcut -> shortcut.writes++));

        assertEquals("product-icon", hostNative.icon);
        assertEquals("host/MainActivity", hostNative.intent);
        assertEquals(0, hostNative.writes);
        assertEquals(1, genuineGuest.writes);
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

    private static final class TestShortcut {
        String owner;
        final boolean directHostTarget;
        String icon;
        String intent;
        int repairs;
        int writes;

        TestShortcut(String owner, boolean directHostTarget, String icon, String intent) {
            this.owner = owner;
            this.directHostTarget = directHostTarget;
            this.icon = icon;
            this.intent = intent;
        }
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
