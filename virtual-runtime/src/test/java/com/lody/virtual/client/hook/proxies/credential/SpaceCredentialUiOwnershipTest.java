package com.lody.virtual.client.hook.proxies.credential;

import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class SpaceCredentialUiOwnershipTest {
    private static final String ACTION = "com.google.android.gms.auth.GOOGLE_SIGN_IN";
    @Test public void rootOwnershipRequiresExactUserComponentActionAndReceiver() {
        String session = UUID.randomUUID().toString();
        assertTrue(SpaceCredentialUiOwnership.validRoot(SpaceCredentialUiOwnership.ROOT,
                ACTION, 10, 10, session, true));
        assertFalse(SpaceCredentialUiOwnership.validRoot(SpaceCredentialUiOwnership.ROOT,
                ACTION, 11, 10, session, true));
        assertFalse(SpaceCredentialUiOwnership.validRoot(SpaceCredentialUiOwnership.CHILD,
                ACTION, 10, 10, session, true));
        assertFalse(SpaceCredentialUiOwnership.validRoot(SpaceCredentialUiOwnership.ROOT,
                ACTION + ".OTHER", 10, 10, session, true));
        assertFalse(SpaceCredentialUiOwnership.validRoot(SpaceCredentialUiOwnership.ROOT,
                ACTION, 10, 10, session, false));
    }

    @Test public void previousSingleTaskAssistedRouteCannotAcquireOwnership() {
        assertFalse(SpaceCredentialUiOwnership.validRoot(
                "org.microg.gms.auth.signin.AssistedSignInActivity",
                "com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN",
                10, 10, UUID.randomUUID().toString(), true));
    }

    @Test public void missingOrNonCanonicalUuidCannotAcquireOwnership() {
        for (String invalid : new String[]{null, "", "bad", "1-1-1-1-1"}) {
            assertFalse(SpaceCredentialUiOwnership.validRoot(SpaceCredentialUiOwnership.ROOT,
                    ACTION, 10, 10, invalid, true));
        }
    }
    @Test public void localDeadlineUsesOriginalAbsoluteTimeAndCapsFutureValues() {
        assertEquals(75_000L, SpaceCredentialUiOwnership.remaining(120_000L, 45_000L));
        assertEquals(0L, SpaceCredentialUiOwnership.remaining(120_000L, 120_001L));
        assertEquals(0L, SpaceCredentialUiOwnership.remaining(120_000L, 120_000L));
        assertEquals(120_000L, SpaceCredentialUiOwnership.remaining(500_000L, 1L));
    }
}
