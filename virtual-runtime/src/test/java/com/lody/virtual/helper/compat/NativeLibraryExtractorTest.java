package com.lody.virtual.helper.compat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class NativeLibraryExtractorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractsAllLibrariesForSelectedAbiAndRejectsNestedPaths() throws Exception {
        File apk = createApk(entries(
                "lib/arm64-v8a/libhermes.so", bytes(1, 2, 3),
                "lib/arm64-v8a/libjsi.so", bytes(4, 5),
                "lib/x86_64/libjsi.so", bytes(9),
                "lib/arm64-v8a/nested/libescape.so", bytes(8),
                "assets/libignored.so", bytes(7)));
        File libraryDirectory = temporaryFolder.newFolder("lib");

        assertEquals(2, NativeLibraryExtractor.extractMissing(
                apk, libraryDirectory, "arm64-v8a"));
        assertArrayEquals(bytes(1, 2, 3),
                Files.readAllBytes(new File(libraryDirectory, "libhermes.so").toPath()));
        assertArrayEquals(bytes(4, 5),
                Files.readAllBytes(new File(libraryDirectory, "libjsi.so").toPath()));
        assertFalse(new File(libraryDirectory, "libescape.so").exists());
        assertFalse(new File(libraryDirectory, "libignored.so").exists());
    }

    @Test
    public void replacesCorruptLibraryAndLeavesVerifiedLibraryUntouched() throws Exception {
        File apk = createApk(entries("lib/arm64-v8a/libjsi.so", bytes(1, 2, 3, 4)));
        File libraryDirectory = temporaryFolder.newFolder("lib");
        File destination = new File(libraryDirectory, "libjsi.so");
        Files.write(destination.toPath(), bytes(9));

        assertEquals(1, NativeLibraryExtractor.extractMissing(
                apk, libraryDirectory, "arm64-v8a"));
        assertArrayEquals(bytes(1, 2, 3, 4), Files.readAllBytes(destination.toPath()));
        assertEquals(0, NativeLibraryExtractor.extractMissing(
                apk, libraryDirectory, "arm64-v8a"));
    }

    private File createApk(Map<String, byte[]> entries) throws Exception {
        File apk = temporaryFolder.newFile("split.apk");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(apk))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(item.getKey()));
                output.write(item.getValue());
                output.closeEntry();
            }
        }
        return apk;
    }

    private static Map<String, byte[]> entries(Object... values) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], (byte[]) values[index + 1]);
        }
        return result;
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
