package com.lody.virtual.client.hook.proxies.credential;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SpaceGoogleProviderPolicyTest {
    @Test public void systemRequiredGoogleRequestUsesExactTrustedVirtualSystemProvider() {
        assertTrue(SpaceGoogleProviderPolicy.permits(true, true, true, true, true,
                SpaceGoogleProviderPolicy.PERMISSION, Collections.emptySet()));
        assertTrue(SpaceGoogleProviderPolicy.permits(true, true, false, true, true,
                SpaceGoogleProviderPolicy.PERMISSION, Collections.singleton(SpaceGoogleProviderPolicy.COMPONENT)));
    }
    @Test public void physicalOrUntrustedProviderCannotSatisfyVirtualRequirement() {
        assertFalse(SpaceGoogleProviderPolicy.permits(true, false, true, true, true,
                SpaceGoogleProviderPolicy.PERMISSION, Collections.emptySet()));
        assertFalse(SpaceGoogleProviderPolicy.permits(true, true, true, false, true,
                SpaceGoogleProviderPolicy.PERMISSION, Collections.emptySet()));
    }
    @Test public void explicitAllowlistMustContainSelectedExactGoogleService() {
        assertFalse(SpaceGoogleProviderPolicy.permits(true, true, true, true, true,
                SpaceGoogleProviderPolicy.PERMISSION, Collections.singleton("other/package.Service")));
        assertTrue(SpaceGoogleProviderPolicy.permits(true, true, true, true, true,
                SpaceGoogleProviderPolicy.PERMISSION, new HashSet<>(Arrays.asList(
                        "other/package.Service", SpaceGoogleProviderPolicy.COMPONENT))));
    }
    @Test public void disabledUnprotectedOrWrongTypeFailsClosed() {
        assertFalse(SpaceGoogleProviderPolicy.permits(true, true, true, true, false,
                SpaceGoogleProviderPolicy.PERMISSION, Collections.emptySet()));
        assertFalse(SpaceGoogleProviderPolicy.permits(true, true, true, true, true,
                "android.permission.BIND_OTHER", Collections.emptySet()));
        assertFalse(SpaceGoogleProviderPolicy.permits(false, true, true, true, true,
                SpaceGoogleProviderPolicy.PERMISSION, Collections.emptySet()));
    }
}
