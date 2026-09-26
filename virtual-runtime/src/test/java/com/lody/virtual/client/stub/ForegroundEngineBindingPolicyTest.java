package com.lody.virtual.client.stub;

import org.junit.Test;
import static org.junit.Assert.*;

public class ForegroundEngineBindingPolicyTest {
    @Test public void onlyExactHostEngineServiceBypassesVirtualResolution() {
        assertTrue(ForegroundEngineBindingPolicy.isEngineBinding("host", "host",
                ForegroundEngineBindingPolicy.SERVICE_CLASS));
        assertFalse(ForegroundEngineBindingPolicy.isEngineBinding("host", "guest",
                ForegroundEngineBindingPolicy.SERVICE_CLASS));
        assertFalse(ForegroundEngineBindingPolicy.isEngineBinding("host", "host",
                "com.lody.virtual.client.stub.DaemonService"));
        assertFalse(ForegroundEngineBindingPolicy.isEngineBinding(null, null,
                ForegroundEngineBindingPolicy.SERVICE_CLASS));
    }
    @Test public void transitionRetentionIsBounded() {
        assertTrue(ForegroundEngineBindingPolicy.TRANSITION_MILLIS > 0);
        assertTrue(ForegroundEngineBindingPolicy.TRANSITION_MILLIS <= 500);
    }
}
