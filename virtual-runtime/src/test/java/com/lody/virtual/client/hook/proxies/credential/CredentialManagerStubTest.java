package com.lody.virtual.client.hook.proxies.credential;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
