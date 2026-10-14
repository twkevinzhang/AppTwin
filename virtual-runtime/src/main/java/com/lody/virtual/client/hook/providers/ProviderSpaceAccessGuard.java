package com.lody.virtual.client.hook.providers;

/** Operation-time space permission boundary, evaluated before provider argument rewriting. */
final class ProviderSpaceAccessGuard {
    private final ProviderSpaceAccessEnvironment environment;

    ProviderSpaceAccessGuard(ProviderSpaceAccessEnvironment environment) {
        this.environment = environment;
    }

    void enforce(String permissionAuthority, String method, Object[] args) {
        if ("asBinder".equals(method)) return;
        String authority = ProviderAuthorityPolicy.normalize(permissionAuthority);
        if (authority == null) return;
        // Never compare ProviderInfo.authority as a single string: ContactsProvider commonly
        // publishes com.android.contacts;contacts. Gate aliases and URI-scoped acquisition.
        String operationAuthority = null;
        if (args != null) for (Object arg : args) if (environment.isUri(arg)) {
            operationAuthority = ProviderAuthorityPolicy.normalize(environment.authority(arg));
            break;
        }
        if (ProviderAuthorityPolicy.contacts(authority) || ProviderAuthorityPolicy.contacts(operationAuthority)) {
            boolean write = "insert".equals(method) || "bulkInsert".equals(method)
                    || "delete".equals(method) || "update".equals(method);
            if ("openFile".equals(method) || "openAssetFile".equals(method)) {
                boolean afterUri = false;
                if (args != null) for (Object arg : args) {
                    if (environment.isUri(arg)) { afterUri = true; continue; }
                    if (afterUri && arg instanceof String) {
                        write = ((String) arg).contains("w");
                        break;
                    }
                }
            }
            environment.require(write
                    ? "android.permission.WRITE_CONTACTS" : "android.permission.READ_CONTACTS");
            // Unknown call/batch operations can perform both reads and writes.
            if ("call".equals(method) || "applyBatch".equals(method)) {
                environment.require("android.permission.WRITE_CONTACTS");
            }
        }
        if (authority.endsWith(".documents") || authority.contains("photopicker")) {
            Object selected = null;
            String documentMode = null;
            boolean afterUri = false;
            if (args != null) for (Object arg : args) {
                if (environment.isUri(arg)) { selected = arg; afterUri = true; continue; }
                if (afterUri && arg instanceof String && documentMode == null) documentMode = (String) arg;
            }
            if (!environment.hasSelectedRead(selected)) {
                environment.documentDenied(method, selected != null);
                throw new SecurityException("URI was not selected for this space");
            }
            if (MediaProviderAccessPolicy.usesLegacyWriteCapability(environment.sdk())
                    && MediaProviderAccessPolicy.requiresWrite(method, documentMode)) {
                environment.require("android.permission.WRITE_EXTERNAL_STORAGE");
                if (selected == null || !environment.hasHostWrite(selected)) {
                    throw new SecurityException("Selected URI has no write grant for this space");
                }
            }
        }
        if (ProviderAuthorityPolicy.contains(authority, "media") || "media".equals(operationAuthority)) {
            Object uri = null;
            if (args != null) for (Object arg : args) if (environment.isUri(arg)) { uri = arg; break; }
            String mode = null;
            if ("openFile".equals(method) || "openAssetFile".equals(method)) {
                boolean afterUri = false;
                if (args != null) for (Object arg : args) {
                    if (environment.isUri(arg)) { afterUri = true; continue; }
                    if (afterUri && arg instanceof String) { mode = (String) arg; break; }
                }
            }
            boolean selectedReadGranted = environment.hasSelectedRead(uri)
                    && environment.hasHostRead(uri);
            String path = uri == null ? "" : environment.path(uri);
            boolean onlyImages = path != null && path.contains("/images");
            boolean onlyVideos = path != null && path.contains("/video");
            // Preserve the existing audio read behavior; audio mutations still need WRITE.
            boolean readGranted = path != null && path.contains("/audio")
                    || environment.canReadMedia(
                    !onlyVideos, !onlyImages);
            if (!MediaProviderAccessPolicy.usesLegacyWriteCapability(environment.sdk())) {
                // Preserve modern scoped-storage behavior; the platform still checks the operation.
                if (selectedReadGranted || readGranted) return;
                throw new SecurityException("Space media permission denied");
            }
            boolean writeGranted = environment.isGranted(
                    "android.permission.WRITE_EXTERNAL_STORAGE");
            if (!MediaProviderAccessPolicy.allows(method, mode, readGranted, writeGranted, selectedReadGranted)) {
                throw new SecurityException("Space media permission denied");
            }
        }
    }

}
