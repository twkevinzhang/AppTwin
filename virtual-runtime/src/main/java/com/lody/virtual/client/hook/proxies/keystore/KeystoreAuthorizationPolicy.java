package com.lody.virtual.client.hook.proxies.keystore;

import java.util.List;

/**
 * Mirrors Android Keystore's secure-user-id viability check without depending on hidden SDK
 * types. A key is removable only when the caller supplied complete current-authenticator state
 * and none of those authenticators can ever satisfy the key again.
 */
final class KeystoreAuthorizationPolicy {
    enum Decision {
        KEEP,
        DELETE_PERMANENTLY_INVALID
    }

    private KeystoreAuthorizationPolicy() {
    }

    static Decision evaluate(List<Long> keySecureUserIds, Long rootSecureUserId,
                             long[] biometricAuthenticatorIds) {
        if (keySecureUserIds == null || keySecureUserIds.isEmpty()) {
            return Decision.KEEP;
        }
        if (rootSecureUserId == null || biometricAuthenticatorIds == null) {
            return Decision.KEEP;
        }
        if (rootSecureUserId != 0L && keySecureUserIds.contains(rootSecureUserId)) {
            return Decision.KEEP;
        }
        // An empty biometric set is not enough evidence to delete a credential-bound key.
        if (biometricAuthenticatorIds.length == 0) {
            return Decision.KEEP;
        }
        for (long biometricId : biometricAuthenticatorIds) {
            if (!keySecureUserIds.contains(biometricId)) {
                return Decision.DELETE_PERMANENTLY_INVALID;
            }
        }
        return Decision.KEEP;
    }
}
