package com.lody.virtual.helper.compat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceConnectionCompatTest {

    @Test
    public void binderSessionFieldStartsAtAndroid17() {
        assertFalse(ServiceConnectionCompat.usesBinderSession(36));
        assertTrue(ServiceConnectionCompat.usesBinderSession(37));
    }
}
