package com.lody.virtual.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GuestCoroutineExceptionCompatTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void installsOnlyForExactAffectedFacebookLiteRuntime() throws Exception {
        FakeEnvironment environment = new FakeEnvironment(true, false);

        assertTrue(GuestCoroutineExceptionCompat.installIfNeeded(
                "com.facebook.lite", 516101866, 28, environment));

        assertTrue(environment.added);
        assertTrue(environment.providerPresent);
        assertTrue(environment.providerLookupCount == 1);
    }

    @Test
    public void skipsOtherVersionPackageAndOldAndroid() throws Exception {
        assertSkipped("com.facebook.lite", 516101865, 28);
        assertSkipped("jp.naver.line.android", 516101866, 28);
        assertSkipped("com.facebook.lite", 516101866, 27);
    }

    @Test
    public void requiresExactDescriptorAndMissingProvider() throws Exception {
        FakeEnvironment missingDescriptor = new FakeEnvironment(false, false);
        assertFalse(GuestCoroutineExceptionCompat.installIfNeeded(
                "com.facebook.lite", 516101866, 37, missingDescriptor));
        assertFalse(missingDescriptor.added);

        FakeEnvironment alreadyPresent = new FakeEnvironment(true, true);
        assertFalse(GuestCoroutineExceptionCompat.installIfNeeded(
                "com.facebook.lite", 516101866, 37, alreadyPresent));
        assertFalse(alreadyPresent.added);
    }

    @Test
    public void matchesOnlyExactProviderNameInServiceDescriptor() throws Exception {
        File exact = descriptorApk(
                "# generated descriptor\n"
                        + "kotlinx.coroutines.android.AndroidExceptionPreHandler # stale\n");
        File prefixOnly = descriptorApk(
                "kotlinx.coroutines.android.AndroidExceptionPreHandlerReplacement\n");

        assertTrue(GuestCoroutineExceptionCompat.codePathContainsProvider(
                exact.getAbsolutePath()));
        assertFalse(GuestCoroutineExceptionCompat.codePathContainsProvider(
                prefixOnly.getAbsolutePath()));
    }

    @Test
    public void protectsPublishedDexFromWritableDynamicLoading() throws Exception {
        File dexFile = temporaryFolder.newFile("compat.dex");

        GuestCoroutineExceptionCompat.protectDex(dexFile);

        assertFalse(dexFile.canWrite());
    }

    @Test
    public void doesNotResolveProviderAfterEarlyDexInjection() throws Exception {
        FakeEnvironment environment = new FakeEnvironment(true, false);
        environment.exposeProviderAfterAdd = false;

        assertTrue(GuestCoroutineExceptionCompat.installIfNeeded(
                "com.facebook.lite", 516101866, 37, environment));
        assertTrue(environment.providerLookupCount == 1);
    }

    private static void assertSkipped(String packageName, int versionCode, int sdkInt)
            throws Exception {
        FakeEnvironment environment = new FakeEnvironment(true, false);
        assertFalse(GuestCoroutineExceptionCompat.installIfNeeded(
                packageName, versionCode, sdkInt, environment));
        assertFalse(environment.added);
    }

    private File descriptorApk(String descriptor) throws Exception {
        File apk = temporaryFolder.newFile();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(apk))) {
            output.putNextEntry(new ZipEntry(
                    "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return apk;
    }

    private static final class FakeEnvironment
            implements GuestCoroutineExceptionCompat.Environment {
        final boolean descriptorPresent;
        boolean providerPresent;
        boolean exposeProviderAfterAdd = true;
        boolean added;
        int providerLookupCount;

        FakeEnvironment(boolean descriptorPresent, boolean providerPresent) {
            this.descriptorPresent = descriptorPresent;
            this.providerPresent = providerPresent;
        }

        @Override
        public boolean descriptorContainsProvider() {
            return descriptorPresent;
        }

        @Override
        public boolean providerClassExists() {
            providerLookupCount++;
            return providerPresent;
        }

        @Override
        public void addCompatDexPath() {
            added = true;
            if (exposeProviderAfterAdd) {
                providerPresent = true;
            }
        }
    }
}
