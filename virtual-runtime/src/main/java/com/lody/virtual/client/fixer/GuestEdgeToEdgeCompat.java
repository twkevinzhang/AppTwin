package com.lody.virtual.client.fixer;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.TypedArray;
import android.os.Build;
import android.view.Window;

import mirror.com.android.internal.R_Hide;

/** Applies Android 15 edge-to-edge enforcement to eligible guest activities. */
public final class GuestEdgeToEdgeCompat {

    private static final int EDGE_TO_EDGE_ENFORCEMENT_SDK = 35;

    private GuestEdgeToEdgeCompat() {
    }

    public static void apply(Activity activity, ActivityInfo activityInfo) {
        Integer targetSdkVersion = targetSdkVersion(activityInfo);
        if (activity == null
                || !shouldApply(Build.VERSION.SDK_INT, targetSdkVersion, Boolean.FALSE)) {
            return;
        }

        TypedArray windowAttributes = null;
        try {
            int[] windowStyleable = R_Hide.styleable.Window.get();
            int windowIsFloating = R_Hide.styleable.Window_windowIsFloating.get();
            if (windowStyleable == null
                    || windowIsFloating < 0
                    || windowIsFloating >= windowStyleable.length) {
                return;
            }

            // AppInstrumentation invokes this after applying ActivityInfo.theme, so this lookup
            // resolves the guest theme rather than the host/stub activity theme.
            windowAttributes = activity.obtainStyledAttributes(windowStyleable);
            if (windowAttributes == null || !windowAttributes.hasValue(windowIsFloating)) {
                return;
            }
            boolean isFloating = windowAttributes.getBoolean(windowIsFloating, true);
            if (!shouldApply(Build.VERSION.SDK_INT, targetSdkVersion, isFloating)) {
                return;
            }

            Window window = activity.getWindow();
            if (window != null) {
                window.setDecorFitsSystemWindows(false);
            }
        } catch (Throwable ignored) {
            // A missing/changed framework styleable must not alter or crash the guest lifecycle.
        } finally {
            if (windowAttributes != null) {
                try {
                    windowAttributes.recycle();
                } catch (Throwable ignored) {
                    // Recycling is best-effort and must preserve the fail-closed lifecycle gate.
                }
            }
        }
    }

    static boolean shouldApply(int sdkInt, Integer targetSdkVersion,
            Boolean windowIsFloating) {
        return sdkInt >= EDGE_TO_EDGE_ENFORCEMENT_SDK
                && targetSdkVersion != null
                && targetSdkVersion >= EDGE_TO_EDGE_ENFORCEMENT_SDK
                && Boolean.FALSE.equals(windowIsFloating);
    }

    private static Integer targetSdkVersion(ActivityInfo activityInfo) {
        if (activityInfo == null || activityInfo.applicationInfo == null) {
            return null;
        }
        return activityInfo.applicationInfo.targetSdkVersion;
    }
}
