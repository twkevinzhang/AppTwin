package com.lody.virtual.client.hook.proxies.credential;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Removes privileged provider filters before a guest request crosses into the host OS. */
public final class CredentialManagerStub extends BinderInvocationProxy {

    private static final String SERVICE_NAME = "credential";

    public CredentialManagerStub() {
        super(loadStubClass(), SERVICE_NAME);
    }

    CredentialManagerStub(BinderInvocationStub invocationStub) {
        super(invocationStub, SERVICE_NAME);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new SanitizeGetCredentialRequest("executeGetCredential"));
        addMethodProxy(new SanitizeGetCredentialRequest("executePrepareGetCredential"));
        addMethodProxy(new SanitizeGetCredentialRequest("getCandidateCredentials"));
    }

    private static final class SanitizeGetCredentialRequest extends MethodProxy {
        private final String methodName;

        SanitizeGetCredentialRequest(String methodName) {
            this.methodName = methodName;
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (args != null && args.length > 0) {
                clearAllowedProviders(args[0]);
            }
            return true;
        }
    }

    /**
     * The physical host UID cannot hold CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS. Clearing only
     * this mutable filter preserves every credential option and lets normal provider discovery run.
     */
    static int clearAllowedProviders(Object request) {
        if (request == null) {
            return 0;
        }
        try {
            Method optionsMethod = request.getClass().getMethod("getCredentialOptions");
            Object optionsValue = optionsMethod.invoke(request);
            if (!(optionsValue instanceof List)) {
                return 0;
            }
            int cleared = 0;
            for (Object option : (List<?>) optionsValue) {
                if (option == null) {
                    continue;
                }
                Method providersMethod = option.getClass().getMethod("getAllowedProviders");
                Object providersValue = providersMethod.invoke(option);
                if (providersValue instanceof Set && !((Set<?>) providersValue).isEmpty()) {
                    ((Set<?>) providersValue).clear();
                    cleared++;
                }
            }
            return cleared;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static Class<?> loadStubClass() {
        try {
            return Class.forName("android.credentials.ICredentialManager$Stub");
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Credential manager binder is unavailable", error);
        }
    }
}
