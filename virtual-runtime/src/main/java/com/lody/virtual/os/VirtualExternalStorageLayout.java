package com.lody.virtual.os;

import java.io.File;

/** Builds host-owned external storage paths used by guest I/O redirection. */
public final class VirtualExternalStorageLayout {

    private VirtualExternalStorageLayout() {
    }

    public static File sharedStorageBase(File hostExternalFilesDir) {
        return new File(hostVirtualRoot(hostExternalFilesDir), "vsdcard");
    }

    public static File sharedStorageForUser(File hostExternalFilesDir, int userId) {
        return new File(sharedStorageBase(hostExternalFilesDir),
                String.valueOf(userId));
    }

    public static File privateStorageForUser(File hostExternalFilesDir, int userId) {
        return new File(hostVirtualRoot(hostExternalFilesDir),
                String.valueOf(userId));
    }

    private static File hostVirtualRoot(File hostExternalFilesDir) {
        return new File(hostExternalFilesDir, "virtual");
    }
}
