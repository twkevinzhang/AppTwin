package com.lody.virtual.server.pm;

import java.io.File;

/** Keeps all post-copy parsing and native extraction rooted in host-private activated bytes. */
final class ActivatedPackageInputs {
    private ActivatedPackageInputs() {
    }

    static File nativeLibrarySource(File privateBaseApk) {
        return privateBaseApk;
    }

    static File parseRoot(File privateBaseApk, File privateAppDirectory, boolean hasSplits) {
        return hasSplits ? privateAppDirectory : privateBaseApk;
    }
}
