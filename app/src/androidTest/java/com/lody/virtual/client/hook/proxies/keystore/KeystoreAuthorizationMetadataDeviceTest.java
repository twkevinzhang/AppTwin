package com.lody.virtual.client.hook.proxies.keystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.IBinder;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in physical-device proof for hidden Keystore2 metadata and authenticator SID access. */
@RunWith(AndroidJUnit4.class)
public class KeystoreAuthorizationMetadataDeviceTest {
    private static final String OPT_IN_ARGUMENT = "keystoreAuthorizationMetadataE2e";
    private static final String ALIAS = "apptwin-keystore-authorization-device-test";

    @Test
    public void currentCredentialBoundKeyIsRecognizedAndCleanedUp() throws Exception {
        assumeOptedIn();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        keyStore.deleteEntry(ALIAS);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(30)
                    .build());
            generator.generateKeyPair();

            Object response = getPhysicalKeyEntry(ALIAS);
            assertNotNull(response);
            List<Long> keySecureUserIds =
                    KeystoreAuthorizationMetadata.keySecureUserIds(response);
            assertNotNull("stable Keystore2 metadata must be readable", keySecureUserIds);
            assertFalse("credential-bound key must expose a secure user id",
                    keySecureUserIds.isEmpty());
            assertEquals(KeystoreAuthorizationPolicy.Decision.KEEP,
                    KeystoreAuthorizationMetadata.evaluate(response, context));
        } finally {
            keyStore.deleteEntry(ALIAS);
            assertFalse("fixture key must be removed", keyStore.containsAlias(ALIAS));
        }
    }

    @Test
    public void permanentlyInvalidatedServiceResponseIsRecognized() throws Exception {
        assumeOptedIn();
        Class<?> responseCode = Class.forName("android.system.keystore2.ResponseCode");
        int expected = responseCode
                .getField("KEY_PERMANENTLY_INVALIDATED")
                .getInt(null);
        Throwable response = (Throwable) Class.forName("android.os.ServiceSpecificException")
                .getConstructor(int.class, String.class)
                .newInstance(expected, "test-only response");

        assertTrue(KeystoreResponsePolicy.isPermanentlyInvalidated(response));
        assertTrue(KeystoreResponsePolicy.requiresKeyReset(response));

        int missing = responseCode.getField("KEY_NOT_FOUND").getInt(null);
        Throwable missingResponse = (Throwable) Class.forName(
                        "android.os.ServiceSpecificException")
                .getConstructor(int.class, String.class)
                .newInstance(missing, "test-only response");
        assertTrue(KeystoreResponsePolicy.requiresKeyReset(missingResponse));
    }

    private static void assumeOptedIn() {
        assumeTrue("Keystore metadata E2E is opt-in; pass -e " + OPT_IN_ARGUMENT + " 1",
                "1".equals(InstrumentationRegistry.getArguments()
                        .getString(OPT_IN_ARGUMENT)));
    }

    private static Object getPhysicalKeyEntry(String alias) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(
                null, "android.system.keystore2.IKeystoreService/default");
        assertNotNull("Keystore2 Binder must be available", binder);

        Class<?> stub = Class.forName("android.system.keystore2.IKeystoreService$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        Object service = asInterface.invoke(null, binder);
        assertNotNull(service);

        Class<?> descriptorType = Class.forName("android.system.keystore2.KeyDescriptor");
        Object descriptor = descriptorType.getConstructor().newInstance();
        setField(descriptor, "domain", 0);
        setField(descriptor, "nspace", -1L);
        setField(descriptor, "alias", alias);
        Method getKeyEntry = service.getClass().getMethod("getKeyEntry", descriptorType);
        return getKeyEntry.invoke(service, descriptor);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getField(name);
        field.set(target, value);
    }
}
