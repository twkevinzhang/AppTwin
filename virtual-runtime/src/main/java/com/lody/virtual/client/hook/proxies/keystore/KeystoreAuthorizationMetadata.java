package com.lody.virtual.client.hook.proxies.keystore;

import android.content.Context;
import android.hardware.biometrics.BiometricManager;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Reads the hidden Keystore2 authorization parcelables through their stable AIDL shape. */
final class KeystoreAuthorizationMetadata {
    // KeyMint Tag::USER_SECURE_ID (ULONG_REP | 502). This value is part of the stable KeyMint ABI.
    static final int USER_SECURE_ID_TAG = 0xA00001F6;

    private KeystoreAuthorizationMetadata() {
    }

    static KeystoreAuthorizationPolicy.Decision evaluate(Object keyEntryResponse,
                                                          Context context) {
        CurrentAuthenticatorIds current = currentAuthenticatorIds(context);
        if (current == null) return KeystoreAuthorizationPolicy.Decision.KEEP;
        List<Long> keyIds = keySecureUserIds(keyEntryResponse);
        return KeystoreAuthorizationPolicy.evaluate(
                keyIds, current.rootSecureUserId, current.biometricAuthenticatorIds);
    }

    static List<Long> keySecureUserIds(Object keyEntryResponse) {
        if (keyEntryResponse == null) return null;
        try {
            Object metadata = publicField(keyEntryResponse, "metadata");
            Object authorizations = publicField(metadata, "authorizations");
            if (authorizations == null || !authorizations.getClass().isArray()) return null;

            List<Long> secureUserIds = new ArrayList<>();
            for (int index = 0; index < Array.getLength(authorizations); index++) {
                Object authorization = Array.get(authorizations, index);
                Object parameter = publicField(authorization, "keyParameter");
                Object tag = publicField(parameter, "tag");
                if (!(tag instanceof Number)
                        || ((Number) tag).intValue() != USER_SECURE_ID_TAG) {
                    continue;
                }
                Object value = publicField(parameter, "value");
                Method getter = value.getClass().getMethod("getLongInteger");
                Object secureUserId = getter.invoke(value);
                if (!(secureUserId instanceof Number)) return null;
                secureUserIds.add(((Number) secureUserId).longValue());
            }
            return secureUserIds;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Object publicField(Object target, String name)
            throws ReflectiveOperationException {
        if (target == null) throw new NoSuchFieldException(name);
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static CurrentAuthenticatorIds currentAuthenticatorIds(Context context) {
        if (context == null) return null;
        try {
            Class<?> gateKeeper = Class.forName("android.security.GateKeeper");
            Method getSecureUserId = gateKeeper.getDeclaredMethod("getSecureUserId");
            getSecureUserId.setAccessible(true);
            Object rootSid = getSecureUserId.invoke(null);
            if (!(rootSid instanceof Number)) return null;

            BiometricManager biometricManager =
                    context.getSystemService(BiometricManager.class);
            if (biometricManager == null) return null;
            Method getAuthenticatorIds =
                    biometricManager.getClass().getMethod("getAuthenticatorIds");
            Object biometricIds = getAuthenticatorIds.invoke(biometricManager);
            if (!(biometricIds instanceof long[])) return null;
            return new CurrentAuthenticatorIds(
                    ((Number) rootSid).longValue(), (long[]) biometricIds);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static final class CurrentAuthenticatorIds {
        final long rootSecureUserId;
        final long[] biometricAuthenticatorIds;

        CurrentAuthenticatorIds(long rootSecureUserId, long[] biometricAuthenticatorIds) {
            this.rootSecureUserId = rootSecureUserId;
            this.biometricAuthenticatorIds = biometricAuthenticatorIds;
        }
    }
}
