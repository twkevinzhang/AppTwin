package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GmsServiceBindingPolicyTest {
    @Test
    public void rejectsGuestWearableBindingThatCannotPublishService() {
        assertTrue(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                GmsServiceBindingPolicy.GMS_PACKAGE));
    }

    @Test
    public void preservesGmsInternalAndUnrelatedBindings() {
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                GmsServiceBindingPolicy.GMS_PACKAGE,
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                GmsServiceBindingPolicy.GMS_PACKAGE));
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                "com.google.android.gms.auth.api.signin.service.START",
                GmsServiceBindingPolicy.GMS_PACKAGE));
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                GmsServiceBindingPolicy.WEARABLE_BIND_ACTION,
                "example.service"));
        assertFalse(GmsServiceBindingPolicy.shouldRejectUnavailableWearableBinding(
                "jp.naver.line.android",
                null,
                GmsServiceBindingPolicy.GMS_PACKAGE));
    }
}
