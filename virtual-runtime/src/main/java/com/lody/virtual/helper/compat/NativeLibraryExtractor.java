package com.lody.virtual.helper.compat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts ABI-matched native libraries when the platform helper is unavailable or incomplete. */
final class NativeLibraryExtractor {

    private NativeLibraryExtractor() {
    }

    static int extractMissing(File apkFile, File libraryDirectory, String abi)
            throws IOException {
        File canonicalDirectory = libraryDirectory.getCanonicalFile();
        if (!canonicalDirectory.isDirectory() && !canonicalDirectory.mkdirs()) {
            throw new IOException("Unable to create native library directory: "
                    + canonicalDirectory);
        }

        String prefix = "lib/" + abi + "/";
        int extracted = 0;
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String libraryName = libraryName(entry, prefix);
                if (libraryName == null) {
                    continue;
                }

                File destination = new File(canonicalDirectory, libraryName).getCanonicalFile();
                if (!canonicalDirectory.equals(destination.getParentFile())) {
                    throw new IOException("Native library escaped destination: " + entry.getName());
                }
                if (matches(destination, entry)) {
                    continue;
                }
                extract(zipFile, entry, destination, canonicalDirectory);
                extracted++;
            }
        }
        return extracted;
    }

    private static String libraryName(ZipEntry entry, String prefix) {
        String name = entry.getName();
        if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".so")) {
            return null;
        }
        String relativeName = name.substring(prefix.length());
        return relativeName.isEmpty() || relativeName.indexOf('/') >= 0
                || relativeName.indexOf('\\') >= 0 ? null : relativeName;
    }

    private static boolean matches(File destination, ZipEntry entry) throws IOException {
        if (!destination.isFile() || entry.getSize() < 0
                || destination.length() != entry.getSize()) {
            return false;
        }
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(destination))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                crc.update(buffer, 0, count);
            }
        }
        return crc.getValue() == entry.getCrc();
    }

    private static void extract(ZipFile zipFile, ZipEntry entry, File destination,
                                File directory) throws IOException {
        File temporary = File.createTempFile("native-", ".tmp", directory);
        boolean renamed = false;
        try {
            try (InputStream input = new BufferedInputStream(zipFile.getInputStream(entry));
                 FileOutputStream rawOutput = new FileOutputStream(temporary);
                 BufferedOutputStream output = new BufferedOutputStream(rawOutput)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                rawOutput.getFD().sync();
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Unable to replace native library: " + destination);
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Unable to activate native library: " + destination);
            }
            renamed = true;
            // Guest code is immutable after installation and must be executable by the linker.
            destination.setReadable(true, false);
            destination.setExecutable(true, false);
            destination.setWritable(false, false);
        } finally {
            if (!renamed) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }
}
