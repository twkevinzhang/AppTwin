package com.lody.virtual.client.hook.proxies.media.session;

/** Only the physical caller package of the verified targeted media-key Binder shape. */
final class MediaKeyCallerIdentityPolicy {
    static final String METHOD = "dispatchMediaKeyEventToSessionAsSystemService";

    private MediaKeyCallerIdentityPolicy() { }

    static void rewriteCaller(String methodName, String returnType, String[] parameterTypes,
            Object[] args, String guestPackage, String hostPackage) {
        // AIDL: boolean ...(String packageName, KeyEvent keyEvent, MediaSession.Token token).
        // Never search argument values for another package or touch the target session.
        if (!METHOD.equals(methodName) || !"boolean".equals(returnType)
                || parameterTypes == null || parameterTypes.length != 3
                || !"java.lang.String".equals(parameterTypes[0])
                || !"android.view.KeyEvent".equals(parameterTypes[1])
                || !"android.media.session.MediaSession$Token".equals(parameterTypes[2])
                || args == null || args.length != 3
                || guestPackage == null || guestPackage.length() == 0
                || hostPackage == null || hostPackage.length() == 0
                || !guestPackage.equals(args[0])) {
            return;
        }
        args[0] = hostPackage;
    }
}
