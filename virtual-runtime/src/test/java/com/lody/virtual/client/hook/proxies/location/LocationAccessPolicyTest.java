package com.lody.virtual.client.hook.proxies.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;

import com.lody.virtual.client.hook.base.LogInvocation;

import org.junit.Test;

public class LocationAccessPolicyTest {

    @Test
    public void grantsLocationWhenEitherHostPermissionIsGranted() {
        assertTrue(LocationAccessPolicy.hasAnyLocationPermission(
                PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED));
        assertTrue(LocationAccessPolicy.hasAnyLocationPermission(
                PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED));
        assertFalse(LocationAccessPolicy.hasAnyLocationPermission(
                PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED));
    }

    @Test
    public void returnsBinderSafeDefaultsWhenLocationIsDenied() {
        assertNull(LocationAccessPolicy.deniedResult(Void.TYPE));
        assertNull(LocationAccessPolicy.deniedResult(Object.class));
        assertEquals(false, LocationAccessPolicy.deniedResult(Boolean.TYPE));
        assertEquals(0, LocationAccessPolicy.deniedResult(Integer.TYPE));
        assertEquals(0L, LocationAccessPolicy.deniedResult(Long.TYPE));
    }

    @Test
    public void locationHookOnlyLogsFailures() {
        LogInvocation logging = LocationManagerStub.class.getAnnotation(LogInvocation.class);
        assertEquals(LogInvocation.Condition.ON_ERROR, logging.value());
    }

    @Test
    public void android12ListenerAndProviderMethodsAreHooked() {
        assertEquals("registerLocationListener",
                new MethodProxies.RegisterLocationListener().getMethodName());
        assertEquals("isProviderEnabledForUser",
                new MethodProxies.IsProviderEnabledForUser().getMethodName());
    }
}
