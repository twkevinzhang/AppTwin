package com.lody.virtual.os;

import java.io.File;

/** Builds host-owned external storage paths used by guest I/O redirection. */
public final class VirtualExternalStorageLayout {

    private VirtualExternalStorageLayout() {
    }

    public static File sharedStorageBase(File externalStorageRoot, String hostPackage) {
        return new File(hostVirtualRoot(externalStorageRoot, hostPackage), "vsdcard");
    }

    public static File sharedStorageForUser(File externalStorageRoot, String hostPackage,
                                            int userId) {
        return new File(sharedStorageBase(externalStorageRoot, hostPackage),
                String.valueOf(userId));
    }

    public static File privateStorageForUser(File externalStorageRoot, String hostPackage,
                                             int userId) {
        return new File(hostVirtualRoot(externalStorageRoot, hostPackage),
                String.valueOf(userId));
    }

    private static File hostVirtualRoot(File externalStorageRoot, String hostPackage) {
        File hostExternalRoot = new File(
                new File(new File(externalStorageRoot, "Android"), "data"), hostPackage);
        return new File(hostExternalRoot, "virtual");
    }
}
