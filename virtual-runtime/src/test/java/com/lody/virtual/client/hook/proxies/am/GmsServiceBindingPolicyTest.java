package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GmsServiceBindingPolicyTest {

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
    public void allowsGmsSelfBindingToWearableService() {
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                GmsServiceBindingPolicy.GMS_PACKAGE,
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                GmsServiceBindingPolicy.GMS_PACKAGE));
    }
}
