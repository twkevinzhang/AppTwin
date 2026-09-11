package com.lody.virtual.client.fixer;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.lody.virtual.helper.utils.Reflect;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;

/** Opt-in, finite geometry probes. Never reads text, intents, or content descriptions. */
public final class GuestWindowDiagnostics {
    private static final String TAG = "GuestWindowPolicy";
    private static final String PROPERTY = "debug.apptwin.window_policy";
    private static final long[] DELAYS_MS = {0, 500, 2000, 8000};
    private static final WeakHashMap<Activity, Integer> BATCHES = new WeakHashMap<>();
    private static int processBatches;
    private static int policyRecords;

    private GuestWindowDiagnostics() { }

    static boolean enabledValue(String value) {
        return "1".equals(value);
    }

    static boolean permitsBatch(boolean enabled, int activityBatches, int totalBatches) {
        return enabled && activityBatches >= 0 && activityBatches < 3
                && totalBatches >= 0 && totalBatches < 12;
    }

    private static boolean enabled() {
        try {
            return enabledValue(Reflect.on(Class.forName("android.os.SystemProperties"))
                    .call("get", PROPERTY, "0").<String>get());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void policy(Activity activity, Integer target, Boolean floating, Boolean optOut,
            boolean applied, String result) {
        try {
            if (!enabled()) return;
            synchronized (BATCHES) {
                if (policyRecords >= 48) return;
                policyRecords++;
            }
            Log.i(TAG, "decision activity=" + (activity == null ? "null" : activity.getClass().getName())
                    + " sdk=" + Build.VERSION.SDK_INT + " target=" + target
                    + " floating=" + floating + " optOut=" + optOut
                    + " applied=" + applied + " result=" + result);
        } catch (Throwable ignored) {
            // Logging must not alter the policy or guest lifecycle.
        }
    }

    public static void afterLifecycle(Activity activity, String phase) {
        try {
            if (activity == null || !enabled()) return;
            Window window = activity.getWindow();
            View decor = window == null ? null : window.peekDecorView();
            if (decor == null) return;
            synchronized (BATCHES) {
                Integer previous = BATCHES.get(activity);
                int count = previous == null ? 0 : previous;
                if (!permitsBatch(true, count, processBatches)) return;
                BATCHES.put(activity, count + 1);
                processBatches++;
            }
            WeakReference<Activity> reference = new WeakReference<>(activity);
            for (long delay : DELAYS_MS) {
                decor.postDelayed(() -> {
                    Activity current = reference.get();
                    if (current == null || current.isFinishing() || current.isDestroyed()
                            || !enabled()) return;
                    try {
                        snapshot(current, phase, delay);
                    } catch (Throwable error) {
                        Log.i(TAG, "snapshot-error=" + error.getClass().getSimpleName());
                    }
                }, delay);
            }
        } catch (Throwable ignored) {
            // Diagnostics must not affect guest lifecycle.
        }
    }

    private static void snapshot(Activity activity, String phase, long delay) {
        Window window = activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (decor == null) return;
        String sample = Integer.toHexString(System.identityHashCode(activity)) + ":" + phase + ":" + delay;
        WindowManager.LayoutParams attrs = window.getAttributes();
        Log.i(TAG, "sample=" + sample + " activity=" + activity.getClass().getName()
                + " flags=" + attrs.flags + " softInput=" + attrs.softInputMode
                + " cutoutMode=" + (Build.VERSION.SDK_INT >= 28 ? attrs.layoutInDisplayCutoutMode : -1)
                + " windowSize=" + attrs.width + "x" + attrs.height
                + " decorSystemUi=" + decor.getSystemUiVisibility());
        if (Build.VERSION.SDK_INT >= 23) {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                Log.i(TAG, "sample=" + sample + " systemInsets=" + insets.getSystemWindowInsetLeft()
                        + "," + insets.getSystemWindowInsetTop() + "," + insets.getSystemWindowInsetRight()
                        + "," + insets.getSystemWindowInsetBottom()
                        + " stableInsets=" + insets.getStableInsetLeft() + "," + insets.getStableInsetTop()
                        + "," + insets.getStableInsetRight() + "," + insets.getStableInsetBottom());
            }
        }
        Set<View> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
        emit(sample, decor, emitted);
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(decor);
        int visited = 0;
        int surfaces = 0;
        while (!pending.isEmpty() && visited++ < 2048 && emitted.size() < 96) {
            View view = pending.removeFirst();
            if (view instanceof SurfaceView) {
                surfaces++;
                View ancestor = view;
                for (int depth = 0; ancestor != null && depth < 32 && emitted.size() < 96; depth++) {
                    emit(sample, ancestor, emitted);
                    ViewParent parent = ancestor.getParent();
                    ancestor = parent instanceof View ? (View) parent : null;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && pending.size() < 2048; i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
        Log.i(TAG, "sample=" + sample + " visited=" + visited + " surfaces=" + surfaces
                + " emitted=" + emitted.size() + " truncated=" + !pending.isEmpty());
    }

    private static void emit(String sample, View view, Set<View> emitted) {
        if (!emitted.add(view)) return;
        int[] screen = new int[2];
        int[] window = new int[2];
        view.getLocationOnScreen(screen);
        view.getLocationInWindow(window);
        Rect visible = new Rect();
        boolean visibleResult = view.getGlobalVisibleRect(visible);
        String id = Integer.toHexString(view.getId());
        if (view.getId() != View.NO_ID) {
            try { id = view.getResources().getResourceName(view.getId()); }
            catch (Throwable ignored) { }
        }
        String margins = "none";
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) params;
            margins = m.leftMargin + "," + m.topMargin + "," + m.rightMargin + "," + m.bottomMargin;
        }
        ViewParent parent = view.getParent();
        Log.i(TAG, "sample=" + sample + " node=" + Integer.toHexString(System.identityHashCode(view))
                + " parent=" + (parent == null ? "null" : Integer.toHexString(System.identityHashCode(parent)))
                + " class=" + view.getClass().getName() + " id=" + id
                + " local=" + view.getLeft() + "," + view.getTop() + "," + view.getWidth() + "," + view.getHeight()
                + " screen=" + screen[0] + "," + screen[1] + " window=" + window[0] + "," + window[1]
                + " padding=" + view.getPaddingLeft() + "," + view.getPaddingTop() + "," + view.getPaddingRight() + "," + view.getPaddingBottom()
                + " margin=" + margins + " translation=" + view.getTranslationX() + "," + view.getTranslationY()
                + " scroll=" + view.getScrollX() + "," + view.getScrollY()
                + " fitsSystemWindows=" + view.getFitsSystemWindows()
                + " visibility=" + view.getVisibility() + " visible=" + visibleResult + ":" + visible.toShortString());
    }
}
