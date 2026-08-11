package com.lody.virtual.client;

import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.Method;

/** App-specific bridges for locally bundled dynamic modules that must be ready before first use. */
final class GuestDynamicModuleCompat {

    private static final String TAG = "GuestDynamicModuleCompat";
    private static final String FACEBOOK_LITE = "com.facebook.lite";

    private GuestDynamicModuleCompat() {
    }

    static void afterApplicationCreate(String packageName, ClassLoader classLoader) {
        try {
            preloadKnownModules(packageName, (managerClassName, providerMethodName,
                    loadMethodName, moduleName) -> {
                Class<?> managerClass = Class.forName(managerClassName, false, classLoader);
                Method providerMethod = managerClass.getDeclaredMethod(providerMethodName);
                providerMethod.setAccessible(true);
                Object provider = providerMethod.invoke(null);
                Method loadMethod = provider.getClass().getMethod(loadMethodName, String.class);
                loadMethod.setAccessible(true);
                loadMethod.invoke(provider, moduleName);
            });
        } catch (Throwable error) {
            // Keep application startup fail-open. The guest can still present its own recovery UI,
            // while logs retain the module name and the original reflection/loader failure.
            VLog.w(TAG, "Unable to preload guest dynamic module for " + packageName
                    + ": " + error + " cause=" + error.getCause());
        }
    }

    static void preloadKnownModules(String packageName, ModulePreloader preloader) throws Exception {
        if (FACEBOOK_LITE.equals(packageName)) {
            preloader.preload("X.0KM", "A00", "A05", "msys");
        }
    }

    interface ModulePreloader {
        void preload(String managerClassName, String providerMethodName,
                String loadMethodName, String moduleName) throws Exception;
    }
}
