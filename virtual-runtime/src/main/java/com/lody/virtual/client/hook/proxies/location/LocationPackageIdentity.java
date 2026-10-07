package com.lody.virtual.client.hook.proxies.location;

/** Rewrites only the guest package argument in evolving LocationManager Binder signatures. */
final class LocationPackageIdentity {
    private LocationPackageIdentity() {
    }

    static void replaceGnssNmeaPackage(Object[] args, String guestPackage, String hostPackage) {
        // ILocationManager: listener, packageName, attributionTag, listenerId.
        // Do not rewrite attribution/listener strings even if they equal the package.
        if (args != null && args.length > 1 && guestPackage != null
                && hostPackage != null && guestPackage.equals(args[1])) {
            args[1] = hostPackage;
        }
    }

    static int replaceGuestPackage(Object[] args, String guestPackage, String hostPackage) {
        if (args == null || guestPackage == null || hostPackage == null) {
            return 0;
        }
        int replaced = 0;
        for (int index = 0; index < args.length; index++) {
            if (guestPackage.equals(args[index])) {
                args[index] = hostPackage;
                replaced++;
            }
        }
        return replaced;
    }
}
