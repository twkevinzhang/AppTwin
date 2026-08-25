package com.lody.virtual.client.hook.proxies.credential;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps Facebook Lite's manual-login navigation attached to the visible Bloks sheet. */
final class FacebookLiteCredentialCompat {
    private static final String TAG = "FacebookLiteCredentialCompat";
    private static final long MONITOR_DURATION_MS = 10 * 60_000L;
    private static final long MONITOR_INTERVAL_MS = 1_000L;
    private static final AtomicInteger MONITOR_GENERATION = new AtomicInteger();

    private FacebookLiteCredentialCompat() {
    }

    static void monitorManualLoginNavigation() {
        Application application = VClientImpl.get().getCurrentApplication();
        if (application == null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        int generation = MONITOR_GENERATION.incrementAndGet();
        long deadline = SystemClock.uptimeMillis() + MONITOR_DURATION_MS;
        handler.post(new Runnable() {
            private boolean failureLogged;

            @Override
            public void run() {
                if (MONITOR_GENERATION.get() != generation) return;
                try {
                    if (rebindDetachedSessionDelegates(application)) {
                        VLog.i(TAG, "rebound detached Facebook Lite navigation delegate");
                        return;
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    if (!failureLogged) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        VLog.w(TAG, "unable to monitor Facebook Lite navigation: %s",
                                cause.getClass().getSimpleName());
                        failureLogged = true;
                    }
                }
                if (SystemClock.uptimeMillis() < deadline) {
                    handler.postDelayed(this, MONITOR_INTERVAL_MS);
                }
            }
        });
    }

    private static boolean rebindDetachedSessionDelegates(Application application)
            throws ReflectiveOperationException {
        ClassLoader classLoader = application.getClassLoader();
        View decorView = currentDecorView(classLoader);
        Object visibleDelegate = findBottomSheetDelegate(decorView, true);
        if (visibleDelegate == null) return false;

        List<View> delegateViews = new ArrayList<>();
        collectBottomSheetViews(decorView, delegateViews);
        List<Object> attachedDelegates = new ArrayList<>();
        for (View view : delegateViews) {
            Object delegate = view.getClass().getField("A00").get(view);
            if (delegate != null) attachedDelegates.add(delegate);
        }

        Class<?> sessionManagerClass = classLoader.loadClass("X.17G");
        Object sessionManager = sessionManagerClass.getField("A04").get(null);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> sessions = (Map<Integer, Object>)
                sessionManagerClass.getField("A00").get(sessionManager);
        boolean rebound = false;
        for (Object session : sessions.values()) {
            if (session == null) continue;
            Field delegateField = session.getClass().getField("A04");
            Object delegate = delegateField.get(session);
            if (delegate == null || containsIdentity(attachedDelegates, delegate)) continue;
            // Only repair navigation sessions populated by Facebook. An empty session cannot
            // safely drive a push even when it has a screen id and delegate.
            if (!fieldPresence(session, "A0D") && !fieldPresence(session, "A07")) continue;
            delegateField.set(session, visibleDelegate);
            rebound = true;
        }
        return rebound;
    }

    private static boolean containsIdentity(List<Object> values, Object target) {
        for (Object value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static boolean fieldPresence(Object object, String fieldName)
            throws ReflectiveOperationException {
        return object.getClass().getField(fieldName).get(object) != null;
    }

    private static View currentDecorView(ClassLoader classLoader)
            throws ReflectiveOperationException {
        Class<?> controllerClass = classLoader.loadClass("X.0Hy");
        Object controller = controllerClass.getField("A1Q").get(null);
        Object activity = controller.getClass().getMethod("A0c").invoke(controller);
        if (activity == null) return null;
        Window window = (Window) activity.getClass().getMethod("getWindow").invoke(activity);
        return window == null ? null : window.getDecorView();
    }

    private static void collectBottomSheetViews(View view, List<View> result) {
        if (view == null) return;
        if ("X.1p5".equals(view.getClass().getName())) result.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectBottomSheetViews(group.getChildAt(index), result);
            }
        }
    }

    private static Object findBottomSheetDelegate(View view, boolean requireShown)
            throws ReflectiveOperationException {
        if (view == null || (requireShown && !view.isShown())) return null;
        Object fallback = null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // Later children are drawn above earlier children. Search from the visible top layer.
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                Object delegate = findBottomSheetDelegate(
                        group.getChildAt(index), requireShown);
                if (delegate != null && delegateStackSize(delegate) > 0) return delegate;
                if (fallback == null) fallback = delegate;
            }
        }
        if ("X.1p5".equals(view.getClass().getName())) {
            Object delegate = view.getClass().getField("A00").get(view);
            if (delegate != null) {
                if (delegateStackSize(delegate) > 0) return delegate;
                if (fallback == null) fallback = delegate;
            }
        }
        return fallback;
    }

    private static int delegateStackSize(Object delegate) throws ReflectiveOperationException {
        if (delegate == null) return 0;
        Object stack = delegate.getClass().getField("A0G").get(delegate);
        return stack instanceof Collection ? ((Collection<?>) stack).size() : 0;
    }
}
