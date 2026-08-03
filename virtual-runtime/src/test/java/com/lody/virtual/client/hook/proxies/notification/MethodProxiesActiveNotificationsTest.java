package com.lody.virtual.client.hook.proxies.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

public class MethodProxiesActiveNotificationsTest {
    private static final String HOST_PACKAGE = "org.maskaccounts";

    @Test
    public void recognizesOnlyWellFormedHostRequests() {
        assertTrue(MethodProxies.isHostActiveNotificationsRequest(
                new Object[]{HOST_PACKAGE, 0}, HOST_PACKAGE));
        assertFalse(MethodProxies.isHostActiveNotificationsRequest(
                new Object[]{"jp.naver.line.android", 0}, HOST_PACKAGE));
        assertFalse(MethodProxies.isHostActiveNotificationsRequest(
                new Object[]{null, 0}, HOST_PACKAGE));
        assertFalse(MethodProxies.isHostActiveNotificationsRequest(
                new Object[]{42, 0}, HOST_PACKAGE));
        assertFalse(MethodProxies.isHostActiveNotificationsRequest(
                new Object[0], HOST_PACKAGE));
        assertFalse(MethodProxies.isHostActiveNotificationsRequest(null, HOST_PACKAGE));
        assertFalse(MethodProxies.isHostActiveNotificationsRequest(
                new Object[]{HOST_PACKAGE, 0}, null));
    }

    @Test
    public void delegatesHostAndHidesVirtualGuestResults() throws Throwable {
        FakeNotificationManager service = new FakeNotificationManager();
        Method method = FakeNotificationManager.class.getDeclaredMethod(
                "getAppActiveNotifications", String.class, int.class);
        Object hostResult = MethodProxies.callGetAppActiveNotifications(
                service, method, new Object[]{HOST_PACKAGE, 0}, HOST_PACKAGE);
        Object guestResult = MethodProxies.callGetAppActiveNotifications(
                service, method, new Object[]{"jp.naver.line.android", 0}, HOST_PACKAGE);
        Object malformedResult = MethodProxies.callGetAppActiveNotifications(
                service, method, new Object[]{42, 0}, HOST_PACKAGE);
        Object nullResult = MethodProxies.callGetAppActiveNotifications(
                service, method, null, HOST_PACKAGE);

        assertSame(service.activeNotifications, hostResult);
        assertTrue(((List<?>) guestResult).isEmpty());
        assertTrue(((List<?>) malformedResult).isEmpty());
        assertTrue(((List<?>) nullResult).isEmpty());
        assertEquals(1, service.callCount);
        assertEquals("getAppActiveNotifications",
                new MethodProxies.GetAppActiveNotifications().getMethodName());
    }

    private static final class FakeNotificationManager {
        final List<String> activeNotifications = java.util.Collections.singletonList(
                "host notification");
        int callCount;

        @SuppressWarnings("unused")
        public List<String> getAppActiveNotifications(String packageName, int userId) {
            callCount++;
            return activeNotifications;
        }
    }
}
