package com.lody.virtual.client.hook.proxies.notification;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class MethodProxiesCancelNotificationTest {

    @Test
    public void modernCancelUsesConsistentPhysicalPackageIdentity() {
        Object[] args = {"org.apptwin", "com.google.android.youtube", "tag", 42, 0};

        MethodProxies.rewriteCancelPackagesForSystem(args, "org.apptwin", true);

        assertArrayEquals(
                new Object[]{"org.apptwin", "org.apptwin", "tag", 42, 0}, args);
    }

    @Test
    public void legacyCancelKeepsTagAtIndexOne() {
        Object[] args = {"org.apptwin", "tag", 42};

        MethodProxies.rewriteCancelPackagesForSystem(args, "org.apptwin", false);

        assertArrayEquals(new Object[]{"org.apptwin", "tag", 42}, args);
    }
}
