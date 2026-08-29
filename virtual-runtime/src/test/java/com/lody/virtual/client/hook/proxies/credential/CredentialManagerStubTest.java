package com.lody.virtual.client.hook.proxies.credential;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CredentialManagerStubTest {

    @Test
    public void bindsEveryGetCredentialRequestEntryPoint() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();

        new CredentialManagerStub(invocationStub);

        assertNotNull(invocationStub.getMethodProxy("executeGetCredential"));
        assertNotNull(invocationStub.getMethodProxy("executePrepareGetCredential"));
        assertNotNull(invocationStub.getMethodProxy("getCandidateCredentials"));
    }

    @Test
    public void clearsOnlyNonEmptyAllowedProviderSets() {
        FakeOption restricted = new FakeOption("password", "provider.one", "provider.two");
        FakeOption unrestricted = new FakeOption("passkey");
        FakeRequest request = new FakeRequest(restricted, unrestricted);

        assertEquals(1, CredentialManagerStub.clearAllowedProviders(request));

        assertEquals("password", restricted.type);
        assertEquals(0, restricted.getAllowedProviders().size());
        assertEquals("passkey", unrestricted.type);
        assertEquals(0, unrestricted.getAllowedProviders().size());
    }

    @Test
    public void leavesUnknownRequestShapesUntouched() {
        assertEquals(0, CredentialManagerStub.clearAllowedProviders(null));
        assertEquals(0, CredentialManagerStub.clearAllowedProviders(new Object()));
    }

    @Test
    public void bypassesOnlyFacebookLiteInteractiveCredentialRequests() {
        assertTrue(CredentialManagerStub.shouldReturnNoCredential(
                "com.facebook.lite", "executeGetCredential"));
        assertFalse(CredentialManagerStub.shouldReturnNoCredential(
                "com.facebook.lite", "executePrepareGetCredential"));
        assertFalse(CredentialManagerStub.shouldReturnNoCredential(
                "jp.naver.line.android", "executeGetCredential"));
    }

    @Test
    public void reportsNoCredentialThroughTheFrameworkCallback() {
        FakeCredentialCallback callback = new FakeCredentialCallback();

        assertTrue(CredentialManagerStub.notifyNoCredential(
                new Object[]{new Object(), callback, "com.facebook.lite"}));
        assertEquals("android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL",
                callback.type);
        assertNotNull(callback.message);
    }

    @Test
    public void preparesManualNavigationBeforeFrameworkError() {
        List<String> calls = new ArrayList<>();
        OrderingCredentialCallback callback = new OrderingCredentialCallback(calls);

        assertTrue(CredentialManagerStub.prepareAndNotifyManualLogin(
                new Object[]{callback},
                () -> calls.add("prepare-navigation")));

        assertEquals(Arrays.asList(
                "prepare-navigation", "framework-onError"), calls);
    }

    @Test
    public void facebookLiteNavigationCompatGatesBothVerifiedVersionsExactly() {
        assertEquals("516101866",
                FacebookLiteCredentialCompat.abiIdForVersion(516101866));
        assertEquals("516201887",
                FacebookLiteCredentialCompat.abiIdForVersion(516201887));
        assertNull(FacebookLiteCredentialCompat.abiIdForVersion(516201886));
        assertNull(FacebookLiteCredentialCompat.abiIdForVersion(516201888));
    }

    @Test
    public void facebookLiteVisibleDelegateSelectionIsIdentityScopedAndFailClosed() {
        Object delegate = new Object();
        assertSame(delegate, FacebookLiteCredentialCompat.uniqueIdentityCandidate(
                Arrays.asList(delegate, delegate)));
        assertNull(FacebookLiteCredentialCompat.uniqueIdentityCandidate(
                Arrays.asList(delegate, new Object())));
        assertNull(FacebookLiteCredentialCompat.uniqueIdentityCandidate(
                new ArrayList<>()));
    }

    @Test
    public void facebookLiteSessionRekeyRequiresOneDifferentIntegerKey() {
        Map<Object, Object> sessions = new HashMap<>();
        sessions.put(41, new Object());
        assertTrue(FacebookLiteCredentialCompat.hasUniqueMismatchedIntegerKey(42, sessions));

        assertFalse(FacebookLiteCredentialCompat.hasUniqueMismatchedIntegerKey(41, sessions));
        sessions.put(40, new Object());
        assertFalse(FacebookLiteCredentialCompat.hasUniqueMismatchedIntegerKey(42, sessions));

        sessions.clear();
        sessions.put("41", new Object());
        assertFalse(FacebookLiteCredentialCompat.hasUniqueMismatchedIntegerKey(42, sessions));
    }

    @Test
    public void facebookLiteOfficialSessionMarkerAcceptsOnlyKnownPopulatedShapes() {
        assertTrue(FacebookLiteCredentialCompat.isOfficialSessionMarker(
                true, null, 0, 0, false));
        assertTrue(FacebookLiteCredentialCompat.isOfficialSessionMarker(
                false, "X.1by", 0, 0, false));
        assertTrue(FacebookLiteCredentialCompat.isOfficialSessionMarker(
                false, null, 1, 1, true));
        assertFalse(FacebookLiteCredentialCompat.isOfficialSessionMarker(
                false, null, 1, 1, false));
        assertFalse(FacebookLiteCredentialCompat.isOfficialSessionMarker(
                false, null, 1, 0, true));
        assertFalse(FacebookLiteCredentialCompat.isOfficialSessionMarker(
                false, "X.1bz", 0, 0, false));
    }

    public static final class FakeCredentialCallback {
        String type;
        String message;

        public void onError(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    public static final class OrderingCredentialCallback {
        private final List<String> calls;

        OrderingCredentialCallback(List<String> calls) {
            this.calls = calls;
        }

        public void onError(String type, String message) {
            calls.add("framework-onError");
        }
    }

    public static final class FakeRequest {
        private final List<FakeOption> options;

        FakeRequest(FakeOption... options) {
            this.options = new ArrayList<>(Arrays.asList(options));
        }

        public List<FakeOption> getCredentialOptions() {
            return options;
        }
    }

    public static final class FakeOption {
        final String type;
        private final Set<String> allowedProviders;

        FakeOption(String type, String... allowedProviders) {
            this.type = type;
            this.allowedProviders = new HashSet<>(Arrays.asList(allowedProviders));
        }

        public Set<String> getAllowedProviders() {
            return allowedProviders;
        }
    }

    /** Records hooks without requiring a live Android Binder. */
    private static final class TestBinderInvocationStub extends BinderInvocationStub {
        private Map<String, MethodProxy> hooks;

        TestBinderInvocationStub() {
            super(new IInterface() {
                @Override
                public IBinder asBinder() {
                    return null;
                }
            });
            hooks = new HashMap<>();
        }

        @Override
        public MethodProxy addMethodProxy(MethodProxy methodProxy) {
            if (hooks != null) {
                hooks.put(methodProxy.getMethodName(), methodProxy);
            }
            return methodProxy;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <H extends MethodProxy> H getMethodProxy(String name) {
            return (H) hooks.get(name);
        }
    }
}
