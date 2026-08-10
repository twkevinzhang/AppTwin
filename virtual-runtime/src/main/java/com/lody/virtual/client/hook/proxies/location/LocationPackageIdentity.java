package com.lody.virtual.client.hook.proxies.location;

/** Rewrites only the guest package argument in evolving LocationManager Binder signatures. */
final class LocationPackageIdentity {
    private LocationPackageIdentity() {
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
