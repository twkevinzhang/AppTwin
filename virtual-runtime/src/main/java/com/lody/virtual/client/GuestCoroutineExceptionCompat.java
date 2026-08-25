package com.lody.virtual.client;

import android.content.Context;

import com.lody.virtual.helper.utils.VLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.BaseDexClassLoader;

/** Narrow compatibility bridge for a stale Facebook Lite coroutine service provider. */
final class GuestCoroutineExceptionCompat {

    private static final String TAG = "GuestCoroutineCompat";
    private static final String FACEBOOK_LITE = "com.facebook.lite";
    private static final int FACEBOOK_LITE_VERSION = 516101866;
    private static final int MIN_SUPPORTED_SDK = 28;
    private static final String PROVIDER_CLASS =
            "kotlinx.coroutines.android.AndroidExceptionPreHandler";
    private static final String DESCRIPTOR =
            "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler";
    private static final String ASSET =
            "guest-compat/facebook-lite-coroutine-provider.jar";

    private GuestCoroutineExceptionCompat() {
    }

    static void beforeApplicationCreate(String packageName, int versionCode, int sdkInt,
            String baseCodePath, String[] splitCodePaths, ClassLoader classLoader,
            Context hostContext) {
        try {
            boolean installed = installIfNeeded(packageName, versionCode, sdkInt,
                    new RuntimeEnvironment(baseCodePath, splitCodePaths, classLoader, hostContext));
            if (installed) {
                VLog.i(TAG, "Installed Facebook Lite coroutine provider compatibility dex");
            }
        } catch (Throwable error) {
            // Compatibility must never prevent a guest from starting. Avoid paths and throwable
            // messages in logs because either can contain app-private implementation details.
            VLog.w(TAG, "Skipped Facebook Lite coroutine provider compatibility: %s",
                    error.getClass().getName());
        }
    }

    static boolean installIfNeeded(String packageName, int versionCode, int sdkInt,
            Environment environment) throws Exception {
        if (!FACEBOOK_LITE.equals(packageName)
                || versionCode != FACEBOOK_LITE_VERSION
                || sdkInt < MIN_SUPPORTED_SDK) {
            return false;
        }
        if (!environment.descriptorContainsProvider() || environment.providerClassExists()) {
            return false;
        }
        environment.addCompatDexPath();
        // Do not resolve the provider here. Facebook installs its transformed Kotlin/coroutine
        // ABI from secondary dex after Application startup; resolving now permanently poisons
        // the class in ART before its superclass is available.
        return true;
    }

    interface Environment {
        boolean descriptorContainsProvider() throws Exception;

        boolean providerClassExists() throws Exception;

        void addCompatDexPath() throws Exception;
    }

    private static final class RuntimeEnvironment implements Environment {
        private final String baseCodePath;
        private final String[] splitCodePaths;
        private final ClassLoader classLoader;
        private final Context hostContext;

        RuntimeEnvironment(String baseCodePath, String[] splitCodePaths,
                ClassLoader classLoader, Context hostContext) {
            this.baseCodePath = baseCodePath;
            this.splitCodePaths = splitCodePaths;
            this.classLoader = classLoader;
            this.hostContext = hostContext;
        }

        @Override
        public boolean descriptorContainsProvider() throws Exception {
            if (codePathContainsProvider(baseCodePath)) {
                return true;
            }
            if (splitCodePaths != null) {
                for (String splitCodePath : splitCodePaths) {
                    if (codePathContainsProvider(splitCodePath)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean providerClassExists() throws Exception {
            try {
                Class.forName(PROVIDER_CLASS, false, classLoader);
                return true;
            } catch (ClassNotFoundException missing) {
                return false;
            }
        }

        @Override
        public void addCompatDexPath() throws Exception {
            if (!(classLoader instanceof BaseDexClassLoader)) {
                throw new IllegalStateException("Guest loader is not BaseDexClassLoader");
            }
            File dexFile = materializeDex(hostContext);
            Method addDexPath = BaseDexClassLoader.class.getDeclaredMethod(
                    "addDexPath", String.class);
            addDexPath.setAccessible(true);
            addDexPath.invoke(classLoader, dexFile.getAbsolutePath());
        }
    }

    static boolean codePathContainsProvider(String codePath) throws Exception {
        if (codePath == null) {
            return false;
        }
        try (ZipFile apk = new ZipFile(codePath)) {
            ZipEntry descriptor = apk.getEntry(DESCRIPTOR);
            if (descriptor == null) {
                return false;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    apk.getInputStream(descriptor), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    String provider = (comment >= 0 ? line.substring(0, comment) : line).trim();
                    if (PROVIDER_CLASS.equals(provider)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static File materializeDex(Context context) throws Exception {
        File directory = new File(context.getCodeCacheDir(), "guest-compat");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create compatibility directory");
        }
        File target = new File(directory, "facebook-lite-coroutine-provider.jar");
        File temporary = new File(directory, "facebook-lite-coroutine-provider.jar.tmp");
        try (InputStream input = context.getAssets().open(ASSET);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace compatibility dex");
        }
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("Unable to publish compatibility dex");
        }
        // Android 14+ refuses dynamically loaded dex files that remain writable. Publish the
        // completed file first, then remove every write bit before exposing it to the loader.
        protectDex(target);
        return target;
    }

    static void protectDex(File dexFile) {
        if (!dexFile.setReadOnly()) {
            throw new IllegalStateException("Unable to protect compatibility dex");
        }
    }
}
