package com.lody.virtual.client.hook.proxies.keystore;

import java.lang.reflect.Field;

/** Recognizes the exact stable Keystore2 error that proves a key can no longer be used. */
final class KeystoreResponsePolicy {
    private static final String SERVICE_EXCEPTION = "android.os.ServiceSpecificException";
    private static final String RESPONSE_CODES = "android.system.keystore2.ResponseCode";
    private static final String PERMANENTLY_INVALIDATED = "KEY_PERMANENTLY_INVALIDATED";
    private static final String KEY_NOT_FOUND = "KEY_NOT_FOUND";

    private KeystoreResponsePolicy() {
    }

    static boolean isPermanentlyInvalidated(Throwable error) {
        if (error == null || !SERVICE_EXCEPTION.equals(error.getClass().getName())) {
            return false;
        }
        try {
            Field errorCode = error.getClass().getField("errorCode");
            Field expected = Class.forName(RESPONSE_CODES).getField(PERMANENTLY_INVALIDATED);
            return matches(errorCode.getInt(error), expected.getInt(null));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean requiresKeyReset(Throwable error) {
        if (isPermanentlyInvalidated(error)) return true;
        if (error == null || !SERVICE_EXCEPTION.equals(error.getClass().getName())) {
            return false;
        }
        try {
            Field errorCode = error.getClass().getField("errorCode");
            Field expected = Class.forName(RESPONSE_CODES).getField(KEY_NOT_FOUND);
            return matches(errorCode.getInt(error), expected.getInt(null));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean matches(int actualErrorCode, int permanentlyInvalidatedErrorCode) {
        return actualErrorCode == permanentlyInvalidatedErrorCode;
    }

}
