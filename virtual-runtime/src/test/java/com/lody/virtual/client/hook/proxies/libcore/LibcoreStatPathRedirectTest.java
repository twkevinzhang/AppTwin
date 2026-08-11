package com.lody.virtual.client.hook.proxies.libcore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LibcoreStatPathRedirectTest {

    @Test
    public void redirectsCanonicalGuestPathBeforeLibcoreStat() {
        String physicalPath = MethodProxies.redirectStatPath(
                "/data/user/0/com.example/app_modules/module/dex",
                path -> path.replace(
                        "/data/user/0/com.example",
                        "/data/user/0/org.apptwin/virtual/data/user/1/com.example"));

        assertEquals(
                "/data/user/0/org.apptwin/virtual/data/user/1/com.example/app_modules/module/dex",
                physicalPath);
    }

    @Test
    public void leavesNullPathNullWithoutCallingRedirector() {
        assertNull(MethodProxies.redirectStatPath(null, path -> {
            throw new AssertionError("redirector must not be called for a null path");
        }));
    }

    @Test
    public void regularGuestReportsKernelOwnerForDexOwnershipChecks() {
        assertEquals(11007, MethodProxies.reportedStatUid(11007, 11007, 11007));
    }

    @Test
    public void isolatedGuestReportsItsNativeOverrideForDexOwnershipChecks() {
        assertEquals(99005, MethodProxies.reportedStatUid(11007, 11007, 99005));
    }

    @Test
    public void unrelatedFileOwnerIsNotRewritten() {
        assertEquals(1000, MethodProxies.reportedStatUid(1000, 11007, 99005));
    }
}
