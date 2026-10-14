package com.lody.virtual.client.hook.proxies.credential;

import java.util.Set;

/** Google ID's system-provider requirement is evaluated in the virtual Space, never cleared. */
final class SpaceGoogleProviderPolicy {
    static final String PACKAGE = "com.google.android.gms";
    static final String SERVICE = "com.google.android.gms.auth.api.credentials.credman.service.GoogleIdService";
    static final String COMPONENT = PACKAGE + "/" + SERVICE;
    static final String ACTION = "android.service.credentials.system.CredentialProviderService";
    static final String PERMISSION = "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE";

    private SpaceGoogleProviderPolicy() { }

    static boolean permits(boolean googleIdType, boolean trustedPinnedInSpace,
                           boolean requiresSystemProvider, boolean declaredSystemProvider,
                           boolean exportedAndEnabled, String servicePermission,
                           Set<String> allowedProviders) {
        if (!googleIdType || !trustedPinnedInSpace || !declaredSystemProvider
                || !exportedAndEnabled || !PERMISSION.equals(servicePermission)) return false;
        // The exact same provider satisfies both ordinary and system-only Google ID requests.
        // An explicit allowlist may contain other alternatives, but must allow this selected provider.
        return allowedProviders != null
                && (allowedProviders.isEmpty() || allowedProviders.contains(COMPONENT));
    }
}
