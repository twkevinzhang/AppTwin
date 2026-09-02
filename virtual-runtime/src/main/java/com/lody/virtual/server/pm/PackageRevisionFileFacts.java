package com.lody.virtual.server.pm;

import java.io.File;
import java.util.Arrays;

/** Cheap facts are only a post-digest cache key; any mismatch fails closed to full verification. */
final class PackageRevisionFileFacts {
    private PackageRevisionFileFacts() {
    }

    static boolean matches(String recordedPath, long recordedSize, long recordedLastModified,
                           File currentFile) {
        return recordedPath != null
                && currentFile != null
                && currentFile.isFile()
                && currentFile.getAbsolutePath().equals(recordedPath)
                && currentFile.length() == recordedSize
                && currentFile.lastModified() == recordedLastModified;
    }

    static boolean sameSplitIdentity(String[] requestedNames, String[] requestedDigests,
                                     String[] recordedNames, String[] recordedDigests) {
        return Arrays.equals(requestedNames, recordedNames)
                && Arrays.equals(requestedDigests, recordedDigests);
    }
}
