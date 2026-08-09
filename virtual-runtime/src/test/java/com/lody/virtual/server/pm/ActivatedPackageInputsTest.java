package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.io.File;

public class ActivatedPackageInputsTest {
    @Test
    public void sourceSwapCannotSelectCallerBytesForNativeExtractionOrReparse() {
        File mutableCallerSource = new File("/caller/staging/base.apk");
        File privateDirectory = new File("/host/private/com.example");
        File activatedBase = new File(privateDirectory, "base.apk");

        assertEquals(activatedBase, ActivatedPackageInputs.nativeLibrarySource(activatedBase));
        assertEquals(activatedBase,
                ActivatedPackageInputs.parseRoot(activatedBase, privateDirectory, false));
        assertEquals(privateDirectory,
                ActivatedPackageInputs.parseRoot(activatedBase, privateDirectory, true));
        assertNotEquals(mutableCallerSource,
                ActivatedPackageInputs.nativeLibrarySource(activatedBase));
    }
}
