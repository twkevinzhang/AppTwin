package com.lody.virtual.client.hook.providers;

import android.os.Build;
import android.os.Bundle;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.hook.base.MethodBox;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author Lody
 */

public class SettingsProviderHook extends ExternalProviderHook {

    private static final String TAG = SettingsProviderHook.class.getSimpleName();

    private static final int METHOD_GET = 0;
    private static final int METHOD_PUT = 1;

    private static final Map<String, String> PRE_SET_VALUES = new HashMap<>();

    static {
        PRE_SET_VALUES.put("user_setup_complete", "1");
        PRE_SET_VALUES.put("install_non_market_apps", "0");
        // Google Services Framework is a privileged system package on the host. When it runs as
        // a guest it cannot read DeviceConfig, so keep its legacy local Gservices storage path.
        // This avoids asking the host Settings provider for READ_DEVICE_CONFIG during Maps M1.
        PRE_SET_VALUES.put("enable_gmscore_gservices_storage", "false");
        // Android 17 rejects this secure setting for non-system callers targeting API > 33.
        // An empty virtual value means no enabled input methods and keeps GMS' optional autofill
        // initialization from terminating the shared Google service process.
        PRE_SET_VALUES.put("enabled_input_methods", "");
    }


    public SettingsProviderHook(Object base) {
        super(base);
    }

    private static int getMethodType(String method) {
        if (method.startsWith("GET_")) {
            return METHOD_GET;
        }
        if (method.startsWith("PUT_")) {
            return METHOD_PUT;
        }
        return -1;
    }

    private static boolean isSecureMethod(String method) {
        return method.endsWith("secure");
    }


    @Override
    public Bundle call(MethodBox methodBox, String method, String arg, Bundle extras) throws InvocationTargetException {
        if ("com.google.android.gsf".equals(VClientImpl.get().getCurrentPackage())) {
            VLog.i(TAG, "maps-m1 gsf settings call method=%s key=%s", method, arg);
        }
        if (!VClientImpl.get().isBound()) {
            return methodBox.call();
        }
        int methodType = getMethodType(method);
        if (METHOD_GET == methodType) {
            String presetValue = presetValue(arg);
            if (presetValue != null) {
                return wrapBundle(arg, presetValue);
            }
            if ("android_id".equals(arg)) {
                return wrapBundle("android_id", VClientImpl.get().getDeviceInfo().androidId);
            }
        }
        if (METHOD_PUT == methodType) {
            if (isSecureMethod(method)) {
                return null;
            }
        }
        try {
            return methodBox.call();
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SecurityException) {
                return null;
            }
            throw e;
        }
    }

    static String presetValue(String key) {
        return PRE_SET_VALUES.get(key);
    }

    /**
     * API 37 enforces hidden/readable Settings metadata before contacting IContentProvider.
     * Add only keys that the virtual provider layer supplies without reading host secure state.
     */
    @SuppressWarnings("unchecked")
    public static boolean allowClientSideRead(Object readableFields, String key) {
        if (!(readableFields instanceof Set) || !PRE_SET_VALUES.containsKey(key)) {
            return false;
        }
        return ((Set<String>) readableFields).add(key);
    }

    @SuppressWarnings("unchecked")
    public static boolean removeClientSideTargetSdkLimit(Object restrictedFields, String key) {
        if (!(restrictedFields instanceof Map) || !PRE_SET_VALUES.containsKey(key)) {
            return false;
        }
        Object previous = ((Map<String, Object>) restrictedFields).put(key, Integer.MAX_VALUE);
        return !Integer.valueOf(Integer.MAX_VALUE).equals(previous);
    }

    private Bundle wrapBundle(String name, String value) {
        Bundle bundle = new Bundle();
        if (Build.VERSION.SDK_INT >= 24) {
            bundle.putString("name", name);
            bundle.putString("value", value);
        } else {
            bundle.putString(name, value);
        }
        return bundle;
    }

    @Override
    protected void processArgs(Method method, Object... args) {
        super.processArgs(method, args);
    }
}
