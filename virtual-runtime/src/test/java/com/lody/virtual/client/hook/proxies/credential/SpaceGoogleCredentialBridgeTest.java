package com.lody.virtual.client.hook.proxies.credential;

import static org.junit.Assert.*;
import org.junit.Test;

/** Request schema and callback boundary regressions without a physical provider or token. */
public class SpaceGoogleCredentialBridgeTest {
    @Test public void delayedResultCannotSucceedAtOrAfterSessionDeadline() {
        assertTrue(SpaceGoogleCredentialBridge.isBeforeDeadline(119999, 120000));
        assertFalse(SpaceGoogleCredentialBridge.isBeforeDeadline(120000, 120000));
        assertFalse(SpaceGoogleCredentialBridge.isBeforeDeadline(120001, 120000));
        assertFalse(SpaceGoogleCredentialBridge.isBeforeDeadline(500000, 120000));
        assertFalse(SpaceGoogleCredentialBridge.isBeforeDeadline(-1, 120000));
    }
    @Test public void emptySpaceNeverStartsAddAccountOrHostChooser() {
        assertFalse(SpaceGoogleCredentialBridge.hasConfiguredSpaceAccounts(0));
        assertFalse(SpaceGoogleCredentialBridge.hasConfiguredSpaceAccounts(-1));
        assertTrue(SpaceGoogleCredentialBridge.hasConfiguredSpaceAccounts(1));
        assertTrue(SpaceGoogleCredentialBridge.hasConfiguredSpaceAccounts(2));
    }
    @Test public void archiveVersionUsesPinnedManifestNotGuestCompatibilityFacade() {
        assertTrue(SpaceGoogleCredentialBridge.isPinnedArchiveVersion(250932030));
        assertFalse(SpaceGoogleCredentialBridge.isPinnedArchiveVersion(263005000));
        assertFalse(SpaceGoogleCredentialBridge.isPinnedArchiveVersion(0));
    }
    @Test public void acceptsOnlyGoogleIdTokenType() {
        assertTrue(SpaceGoogleCredentialBridge.isGoogleType(
                "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"));
        assertFalse(SpaceGoogleCredentialBridge.isGoogleType("android.credentials.TYPE_PASSWORD_CREDENTIAL"));
        assertFalse(SpaceGoogleCredentialBridge.isGoogleType(null));
    }
    @Test public void normalAndExplicitSignInUseDifferentOfficialRequestNamespaces() {
        assertEquals("com.google.android.libraries.identity.googleid.BUNDLE_KEY_",
                SpaceGoogleCredentialBridge.requestPrefix(null));
        assertEquals("com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_",
                SpaceGoogleCredentialBridge.requestPrefix(
                        "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL"));
    }
    @Test(expected = IllegalArgumentException.class)
    public void unknownSubtypeNeverSilentlyDowngradesToGeneralGoogleId() {
        SpaceGoogleCredentialBridge.requestPrefix("unknown-subtype");
    }
    @Test public void typedCreateAndClearFailuresReachOnlyProvidedCallback() {
        Callback callback = new Callback();
        assertTrue(CredentialManagerStub.notifyError(new Object[]{new Object(), callback},
                "android.credentials.CreateCredentialException.TYPE_NO_CREATE_OPTIONS"));
        assertEquals("android.credentials.CreateCredentialException.TYPE_NO_CREATE_OPTIONS", callback.type);
        assertTrue(CredentialManagerStub.notifyError(new Object[]{callback},
                "android.credentials.ClearCredentialStateException.TYPE_UNKNOWN"));
        assertEquals("android.credentials.ClearCredentialStateException.TYPE_UNKNOWN", callback.type);
    }
    @Test public void noCallbackFailsClosedInsteadOfPhysicalFallback() {
        assertNull(SpaceGoogleCredentialBridge.findCallback(new Object[]{new Object()}));
        assertFalse(CredentialManagerStub.notifyError(null, "unused"));
    }
    @Test public void preparedProtocolCompletesOnlyMatchingResponseCallback() {
        PreparedCallback prepared = new PreparedCallback();
        Callback get = new Callback();
        Object response = new Object();
        assertTrue(SpaceGoogleCredentialBridge.notifyResponse(
                new Object[]{get, prepared}, Object.class, response));
        assertSame(response, prepared.response);
        assertNull(get.type);
        assertFalse(SpaceGoogleCredentialBridge.notifyResponse(new Object[]{get}, Object.class, response));
    }
    @Test public void canceledSessionNotifiesOnceAndDiscardsLateActivityResult() {
        Callback callback = new Callback();
        SpaceCredentialCompletion completion = new SpaceCredentialCompletion();
        assertTrue(completion.finish(() -> callback.onError(
                "android.credentials.GetCredentialException.TYPE_USER_CANCELED", "cancel")));
        assertFalse(completion.finish(() -> callback.onError("late-result", "late")));
        assertFalse(completion.finish(() -> callback.onError(
                "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL", "timeout")));
        assertTrue(completion.isFinished());
        assertEquals(1, callback.calls);
        assertEquals("android.credentials.GetCredentialException.TYPE_USER_CANCELED", callback.type);
    }
    @Test public void racingCancellationAndSuccessDeliverExactlyOneTerminalCallback() throws Exception {
        SpaceCredentialCompletion completion = new SpaceCredentialCompletion();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
        Runnable race = () -> {
            try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            completion.finish(deliveries::incrementAndGet);
        };
        Thread canceled = new Thread(race);
        Thread succeeded = new Thread(race);
        canceled.start(); succeeded.start(); start.countDown();
        canceled.join(2000); succeeded.join(2000);
        assertFalse(canceled.isAlive()); assertFalse(succeeded.isAlive());
        assertEquals(1, deliveries.get());
    }
    private static final class PreparedCallback {
        Object response;
        public void onResponse(Object response) { this.response = response; }
    }
    private static final class Callback {
        String type;
        int calls;
        public void onError(String type, String message) { this.type = type; calls++; }
    }
}
