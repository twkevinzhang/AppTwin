package com.lody.virtual.client.hook.proxies.keystore;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.Signature;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Restores Facebook Lite's fixed local attestation key inside its guest namespace. */
final class FacebookLiteAttestationKeyCompat {
    private static final String TAG = "FacebookLiteKeyCompat";
    private static final String PACKAGE_NAME = "com.facebook.lite";
    private static final int VERSION_525_0_0_6_108 = 516101866;
    private static final int VERSION_526_0_0_5_107 = 516201887;
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
        refreshWarmup(packageName, currentVersionCode(packageName));
    }

    private static void refreshWarmup(String packageName, int versionCode) {
        try {
            Application application = VClientImpl.get().getCurrentApplication();
            if (application == null) return;
            WarmupAbi abi = warmupAbiForVersion(versionCode);
            if (abi == null) return;
            Class<?> helperClass = application.getClassLoader().loadClass(abi.helperClass);
            Field providerField = helperClass.getField(abi.providerField);
            Object provider = providerField.get(null);
            Method get = provider.getClass().getMethod("get");
            Object helper = get.invoke(provider);
            Method warmup = helperClass.getMethod("A02", boolean.class);
            warmup.invoke(helper, abi.force);
            VLog.i(TAG, "refreshed Facebook Lite attestation warmup");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            VLog.w(TAG, "unable to refresh Facebook Lite attestation warmup: %s",
                    cause.getClass().getSimpleName());
        }
    }

    static WarmupAbi warmupAbiForVersion(int versionCode) {
        switch (versionCode) {
            case VERSION_525_0_0_6_108:
                return new WarmupAbi("X.0Fs", "A05", false);
            case VERSION_526_0_0_5_107:
                return new WarmupAbi("X.0Fg", "A05", true);
            default:
                return null;
        }
    }

    private static int currentVersionCode(String packageName) {
        PackageInfo packageInfo = VPackageManager.get().getPackageInfo(
                packageName, 0, VUserHandle.myUserId());
        return packageInfo == null ? -1 : packageInfo.versionCode;
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
        } catch (Exception error) {
            if (hasCauseOfType(error, KeyPermanentlyInvalidatedException.class)) return false;
            throw error;
        }
    }

    static boolean hasCauseOfType(Throwable error, Class<? extends Throwable> causeType) {
        if (causeType == null) return false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.add(cause);
                cause = cause.getCause()) {
            if (causeType.isInstance(cause)) return true;
        }
        return false;
    }

    static final class WarmupAbi {
        final String helperClass;
        final String providerField;
        final boolean force;

        WarmupAbi(String helperClass, String providerField, boolean force) {
            this.helperClass = helperClass;
            this.providerField = providerField;
            this.force = force;
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
