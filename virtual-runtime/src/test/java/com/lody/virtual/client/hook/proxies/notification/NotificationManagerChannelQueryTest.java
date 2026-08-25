package com.lody.virtual.client.hook.proxies.notification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

public class NotificationManagerChannelQueryTest {
    private static final String GUEST_PACKAGE = "jp.naver.line.android";
    private static final String HOST_PACKAGE = "org.apptwin";

    @Test
    public void rewritesOldApiOwnPackageWithoutChangingChannelId() {
        Object[] args = {GUEST_PACKAGE, "new-messages"};

        assertTrue(NotificationManagerStub.rewriteOwnGuestPackage(
                args, GUEST_PACKAGE, HOST_PACKAGE));

        assertArrayEquals(new Object[]{HOST_PACKAGE, "new-messages"}, args);
    }

    @Test
    public void rewritesAllOwnPackageSlotsInNewApiSignature() {
        Object[] args = {GUEST_PACKAGE, 12, GUEST_PACKAGE, "new-messages"};

        assertTrue(NotificationManagerStub.rewriteOwnGuestPackage(
                args, GUEST_PACKAGE, HOST_PACKAGE));

        assertArrayEquals(
                new Object[]{HOST_PACKAGE, 12, HOST_PACKAGE, "new-messages"}, args);
    }

    @Test
    public void acceptsNewApiSignatureWhenOperationPackageWasAlreadyNormalized() {
        Object[] args = {HOST_PACKAGE, 12, GUEST_PACKAGE, "new-messages"};

        assertTrue(NotificationManagerStub.rewriteOwnGuestPackage(
                args, GUEST_PACKAGE, HOST_PACKAGE));

        assertArrayEquals(
                new Object[]{HOST_PACKAGE, 12, HOST_PACKAGE, "new-messages"}, args);
    }

    @Test
    public void ownChannelQueryDelegatesToHostAndReturnsTheRequestedChannel() throws Throwable {
        FakeNotificationManager service = new FakeNotificationManager();
        Method method = FakeNotificationManager.class.getDeclaredMethod(
                "getNotificationChannel", String.class, int.class, String.class, String.class);
        Object[] args = {GUEST_PACKAGE, 12, GUEST_PACKAGE, "new-messages"};

        Object result = NotificationManagerStub.callGetOwnNotificationChannel(
                service, method, args, GUEST_PACKAGE, HOST_PACKAGE);

        assertSame(service.channel, result);
        assertEquals(1, service.callCount);
        assertArrayEquals(
                new Object[]{HOST_PACKAGE, 12, HOST_PACKAGE, "new-messages"},
                service.lastArgs);
    }

    @Test
    public void wrongPackageQueryFailsClosedWithoutCallingSystemService() throws Throwable {
        FakeNotificationManager service = new FakeNotificationManager();
        Method method = FakeNotificationManager.class.getDeclaredMethod(
                "getNotificationChannel", String.class, int.class, String.class, String.class);

        Object result = NotificationManagerStub.callGetOwnNotificationChannel(
                service, method,
                new Object[]{"com.example.victim", 12, "com.example.victim", "secret"},
                GUEST_PACKAGE, HOST_PACKAGE);

        assertNull(result);
        assertEquals(0, service.callCount);
    }

    @Test
    public void guestCallingIdentityCannotQueryAnotherTargetPackage() throws Throwable {
        FakeNotificationManager service = new FakeNotificationManager();
        Method method = FakeNotificationManager.class.getDeclaredMethod(
                "getNotificationChannel", String.class, int.class, String.class, String.class);

        Object result = NotificationManagerStub.callGetOwnNotificationChannel(
                service, method,
                new Object[]{GUEST_PACKAGE, 12, "com.example.victim", "secret"},
                GUEST_PACKAGE, HOST_PACKAGE);

        assertNull(result);
        assertEquals(0, service.callCount);
    }

    @Test
    public void rewritesAndroid16ChannelListIdentityToHost() {
        Object[] args = {GUEST_PACKAGE, GUEST_PACKAGE, 12};

        assertTrue(NotificationManagerStub.rewriteOwnGuestChannelListPackages(
                args, GUEST_PACKAGE, HOST_PACKAGE));

        assertArrayEquals(new Object[]{HOST_PACKAGE, HOST_PACKAGE, 12}, args);
    }

    @Test
    public void acceptsChannelListWhenOperationPackageWasAlreadyNormalized() {
        Object[] args = {HOST_PACKAGE, GUEST_PACKAGE, 12};

        assertTrue(NotificationManagerStub.rewriteOwnGuestChannelListPackages(
                args, GUEST_PACKAGE, HOST_PACKAGE));

        assertArrayEquals(new Object[]{HOST_PACKAGE, HOST_PACKAGE, 12}, args);
    }

    @Test
    public void channelOwnershipUsesTheGuestPackageNamespace() {
        assertTrue(NotificationManagerStub.isOwnNotificationChannelId(
                GUEST_PACKAGE + ".notification.NewMessages", GUEST_PACKAGE));
        assertFalse(NotificationManagerStub.isOwnNotificationChannelId(
                "org.apptwin.notification.daemon", GUEST_PACKAGE));
        assertFalse(NotificationManagerStub.isOwnNotificationChannelId(
                "com.example.other.notification.NewMessages", GUEST_PACKAGE));
    }

    private static final class FakeNotificationManager {
        final Object channel = new Object();
        int callCount;
        Object[] lastArgs;

        @SuppressWarnings("unused")
        public Object getNotificationChannel(
                String callingPackage, int userId, String packageName, String channelId) {
            callCount++;
            lastArgs = new Object[]{callingPackage, userId, packageName, channelId};
            return channel;
        }
    }
}
