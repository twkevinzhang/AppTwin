package com.lody.virtual.client.hook.proxies.credential;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.Method;

/** Routes guest credentials within their Space; physical credential providers are never queried. */
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
        addMethodProxy(new SanitizeGetCredentialRequest("executeCreateCredential"));
        addMethodProxy(new SanitizeGetCredentialRequest("clearCredentialState"));
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
            if ("executeGetCredential".equals(methodName)) {
                return SpaceGoogleCredentialBridge.execute(args);
            }
            // Prepared/candidate sessions cannot safely bind a physical provider to a Space.
            if ("executePrepareGetCredential".equals(methodName)) {
                SpaceGoogleCredentialBridge.rejectPrepared(args);
            } else if ("executeCreateCredential".equals(methodName)) {
                notifyError(args, "android.credentials.CreateCredentialException.TYPE_NO_CREATE_OPTIONS");
            } else if ("clearCredentialState".equals(methodName)) {
                notifyError(args, "android.credentials.ClearCredentialStateException.TYPE_UNKNOWN");
            } else {
                SpaceGoogleCredentialBridge.reject(args);
            }
            return null;
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
        return notifyError(args, NO_CREDENTIAL);
    }

    static boolean notifyError(Object[] args, String type) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Method onError = arg.getClass().getMethod(
                        "onError", String.class, String.class);
                onError.setAccessible(true);
                onError.invoke(arg, type,
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

    private static Class<?> loadStubClass() {
        try {
            return Class.forName("android.credentials.ICredentialManager$Stub");
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Credential manager binder is unavailable", error);
        }
    }
}
