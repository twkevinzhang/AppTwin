package com.lody.virtual.client.hook.proxies.credential;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Removes privileged provider filters before a guest request crosses into the host OS. */
public final class CredentialManagerStub extends BinderInvocationProxy {

    private static final String SERVICE_NAME = "credential";
    private static final String FACEBOOK_LITE = "com.facebook.lite";
    private static final String NO_CREDENTIAL =
            "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL";

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
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (args != null && args.length > 0) {
                clearAllowedProviders(args[0]);
            }
            String packageName = VClientImpl.get().getCurrentPackage();
            if (shouldReturnNoCredential(packageName, methodName)
                    && prepareAndNotifyManualLogin(
                            args,
                            FacebookLiteCredentialCompat::prepareManualLoginNavigation)) {
                // Credential Manager launches its UI as a host activity. Returning from that
                // activity detaches Facebook Lite's Bloks screen from its in-memory session map,
                // so keep this one guest on the stable manual-login path.
                VLog.i("CredentialManagerStub",
                        "kept Facebook Lite on manual credential entry");
                FacebookLiteCredentialCompat.monitorManualLoginNavigation();
                return null;
            }
            return method.invoke(who, args);
        }
    }

    static boolean shouldReturnNoCredential(String packageName, String methodName) {
        return FACEBOOK_LITE.equals(packageName) && "executeGetCredential".equals(methodName);
    }

    static boolean prepareAndNotifyManualLogin(
            Object[] args, Runnable prepareNavigation) {
        prepareNavigation.run();
        return notifyNoCredential(args);
    }

    static boolean notifyNoCredential(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Method onError = arg.getClass().getMethod(
                        "onError", String.class, String.class);
                onError.invoke(arg, NO_CREDENTIAL,
                        "Host credentials are unavailable inside this AppTwin guest");
                return true;
            } catch (NoSuchMethodException ignored) {
                // This argument is not the credential callback.
            } catch (ReflectiveOperationException | RuntimeException error) {
                return false;
            }
        }
        return false;
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
