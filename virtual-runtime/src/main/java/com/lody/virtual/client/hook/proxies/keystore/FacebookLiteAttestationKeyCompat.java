package com.lody.virtual.client.hook.proxies.keystore;

import android.os.Build;
import android.app.Application;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.client.VClientImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.Signature;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Restores Facebook Lite's fixed local attestation key inside its guest namespace. */
final class FacebookLiteAttestationKeyCompat {
    private static final String TAG = "FacebookLiteKeyCompat";
    private static final String PACKAGE_NAME = "com.facebook.lite";
    private static final String GUEST_ALIAS = "w6CmevIyM/PL6Q5uUDw=";
    private static final byte[] PROBE = new byte[]{0x41, 0x70, 0x70, 0x54, 0x77, 0x69, 0x6e};
    private static final ThreadLocal<Boolean> IN_PROGRESS = new ThreadLocal<>();
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();

    private FacebookLiteAttestationKeyCompat() {
    }

    static boolean supportsPackage(String packageName) {
        return PACKAGE_NAME.equals(packageName);
    }

    static boolean isAttestationAlias(String packageName, String alias) {
        return supportsPackage(packageName) && GUEST_ALIAS.equals(alias);
    }

    static void refreshWarmupAfterGeneration(String packageName) {
        if (!supportsPackage(packageName) || Boolean.TRUE.equals(IN_PROGRESS.get())) return;
        try {
            Application application = VClientImpl.get().getCurrentApplication();
            if (application == null) return;
            Class<?> helperClass = application.getClassLoader().loadClass("X.0Fs");
            Field providerField = helperClass.getField("A05");
            Object provider = providerField.get(null);
            Method get = provider.getClass().getMethod("get");
            Object helper = get.invoke(provider);
            Method warmup = helperClass.getMethod("A02", boolean.class);
            warmup.invoke(helper, false);
            VLog.i(TAG, "refreshed Facebook Lite attestation warmup after key generation");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            VLog.w(TAG, "unable to refresh Facebook Lite attestation warmup: %s",
                    cause.getClass().getSimpleName());
        }
    }

    static void ensure(String packageName, int userId) {
        if (!supportsPackage(packageName)) return;
        String owner = packageName + ':' + userId;
        if (VERIFIED.contains(owner) || Boolean.TRUE.equals(IN_PROGRESS.get())) return;
        IN_PROGRESS.set(true);
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (!isUsable(keyStore)) {
                keyStore.deleteEntry(GUEST_ALIAS);
                generate(GUEST_ALIAS, true);
                VLog.i(TAG, "restored Facebook Lite guest attestation key user=%d", userId);
            }
            VERIFIED.add(owner);
        } catch (Exception error) {
            VLog.w(TAG, "unable to restore Facebook Lite guest attestation key user=%d: %s",
                    userId, error.getClass().getSimpleName());
        } finally {
            IN_PROGRESS.remove();
        }
    }

    private static boolean isUsable(KeyStore keyStore) throws Exception {
        if (!keyStore.containsAlias(GUEST_ALIAS)) return false;
        KeyStore.Entry entry = keyStore.getEntry(GUEST_ALIAS, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) return false;
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(((KeyStore.PrivateKeyEntry) entry).getPrivateKey());
            signature.update(PROBE);
            signature.sign();
            return true;
        } catch (KeyPermanentlyInvalidatedException invalidated) {
            return false;
        }
    }

    private static void generate(String alias, boolean strongBox) throws Exception {
        Date notBefore = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(notBefore);
        calendar.add(Calendar.YEAR, 10);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(calendar.getTime());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(strongBox);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        try {
            generator.initialize(builder.build());
            generator.generateKeyPair();
        } catch (ProviderException error) {
            if (!strongBox || !(error instanceof StrongBoxUnavailableException)) throw error;
            generate(alias, false);
        }
    }
}
