package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GmsServiceBindingPolicyTest {

    @Test
    public void selectsExplicitPackageWithoutVirtualResolution() {
        assertTrue(GmsServiceBindingPolicy.GMS_PACKAGE.equals(
                GmsServiceBindingPolicy.selectServicePackage(
                        GmsServiceBindingPolicy.GMS_PACKAGE, null, null)));
    }

    @Test
    public void selectsExplicitComponentWithoutVirtualResolution() {
        assertTrue(GmsServiceBindingPolicy.GMS_PACKAGE.equals(
                GmsServiceBindingPolicy.selectServicePackage(
                        null, GmsServiceBindingPolicy.GMS_PACKAGE, null)));
    }

    @Test
    public void fallsBackToResolvedPackageForImplicitIntent() {
        assertTrue(GmsServiceBindingPolicy.GMS_PACKAGE.equals(
                GmsServiceBindingPolicy.selectServicePackage(
                        null, null, GmsServiceBindingPolicy.GMS_PACKAGE)));
    }

    @Test
    public void leavesUnresolvedImplicitIntentUnscoped() {
        assertTrue(GmsServiceBindingPolicy.selectServicePackage(null, null, null) == null);
    }

    @Test
    public void rejectsGuestBindingToGmsWearableService() {
        assertTrue(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                GmsServiceBindingPolicy.GMS_PACKAGE));
    }

    @Test
    public void allowsOtherGmsServiceActions() {
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                "com.google.android.gms.auth.api.signin.service.START",
                GmsServiceBindingPolicy.GMS_PACKAGE));
    }

    @Test
    public void allowsWearableActionForOtherServicePackages() {
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                "example.service"));
    }

    @Test
    public void allowsUnresolvedImplicitWearableAction() {
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                GmsServiceBindingPolicy.selectServicePackage(null, null, null)));
    }

    @Test
    public void allowsGmsSelfBindingToWearableService() {
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                GmsServiceBindingPolicy.GMS_PACKAGE,
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                GmsServiceBindingPolicy.GMS_PACKAGE));
    }

    @Test
    public void allowsServerOwnedBindingWithoutResolvingGuestCallerPackage() {
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                true,
                null,
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                GmsServiceBindingPolicy.GMS_PACKAGE));
    }
}
