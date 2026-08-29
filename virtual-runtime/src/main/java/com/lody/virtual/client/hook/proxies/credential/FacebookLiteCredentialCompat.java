package com.lody.virtual.client.hook.proxies.credential;

import android.app.Application;
import android.app.Dialog;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps Facebook Lite's manual-login navigation attached to the visible Bloks sheet. */
final class FacebookLiteCredentialCompat {
    private static final String TAG = "FacebookLiteCredentialCompat";
    private static final long MONITOR_DURATION_MS = 10 * 60_000L;
    private static final long MONITOR_INTERVAL_MS = 1_000L;
    private static final AtomicInteger MONITOR_GENERATION = new AtomicInteger();
    private static String lastShapeDiagnostic;
    private static String lastIdDiagnostic;

    private static final NavigationAbi LEGACY_ABI = new NavigationAbi(
            "516101866", "X.17G", null, null,
            "X.0Hy", "A0c", "X.1p5", "A00", null,
            null, null, null, null);
    private static final NavigationAbi CURRENT_ABI = new NavigationAbi(
            "516201887", "X.1HG", "X.1HH", "X.1cY",
            "X.0Hu", "A0d", "X.0kQ", "A08", "A02",
            "X.1d2", "A00", "A06", "X.3xq");

    private FacebookLiteCredentialCompat() {
    }

    static void prepareManualLoginNavigation() {
        Application application = VClientImpl.get().getCurrentApplication();
        if (application == null) return;
        try {
            AttemptResult result = rebindDetachedSessionDelegates(application);
            VLog.i(TAG, "navigation prepare=%s", result.checkpoint);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            VLog.w(TAG, "unable to prepare Facebook Lite navigation: %s",
                    cause.getClass().getSimpleName());
        }
    }

    static void monitorManualLoginNavigation() {
        Application application = VClientImpl.get().getCurrentApplication();
        if (application == null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        int generation = MONITOR_GENERATION.incrementAndGet();
        synchronized (FacebookLiteCredentialCompat.class) {
            lastShapeDiagnostic = null;
            lastIdDiagnostic = null;
        }
        long deadline = SystemClock.uptimeMillis() + MONITOR_DURATION_MS;
        handler.post(new Runnable() {
            private boolean failureLogged;
            private String lastCheckpoint;

            @Override
            public void run() {
                if (MONITOR_GENERATION.get() != generation) return;
                try {
                    AttemptResult result = rebindDetachedSessionDelegates(application);
                    if (!result.checkpoint.equals(lastCheckpoint)) {
                        VLog.i(TAG, "navigation checkpoint=%s", result.checkpoint);
                        lastCheckpoint = result.checkpoint;
                    }
                    if (result.rebound) {
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

    private static AttemptResult rebindDetachedSessionDelegates(Application application)
            throws ReflectiveOperationException {
        ClassLoader classLoader = application.getClassLoader();
        String packageName = VClientImpl.get().getCurrentPackage();
        PackageInfo packageInfo = VPackageManager.get().getPackageInfo(
                packageName, 0, VUserHandle.myUserId());
        int versionCode = packageInfo == null ? -1 : packageInfo.versionCode;
        NavigationAbi abi = resolveAbi(classLoader, versionCode);
        View decorView = currentDecorView(classLoader, abi);
        if (decorView == null) return AttemptResult.waiting(abi.id + ":activity-unavailable");

        Class<?> sessionManagerClass = classLoader.loadClass(abi.sessionManagerClass);
        Object sessionManager = sessionManagerClass.getField("A04").get(null);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> sessions = (Map<Integer, Object>)
                sessionManagerClass.getField("A00").get(sessionManager);
        if (sessions == null || sessions.isEmpty()) {
            return AttemptResult.waiting(abi.id + ":sessions-unavailable");
        }

        AttemptResult rekeyResult = repairCurrentSessionKeyAndDelegate(
                classLoader, sessions, abi);
        if (rekeyResult != null) return rekeyResult;

        AttemptResult restoreResult = runExactNavigationRestore(sessions.values(), abi);
        if (restoreResult != null) return restoreResult;

        List<Object> attachedDelegates = new ArrayList<>();
        Object visibleDelegate;
        String unresolvedCheckpoint = abi.id + ":visible-delegate-unavailable";
        if (abi.navigationEntryClass != null) {
            VisibleDelegateResult result = findCurrentVisibleDelegate(
                    classLoader, decorView, sessions.values(), abi, attachedDelegates);
            visibleDelegate = result.delegate;
            unresolvedCheckpoint = abi.id + ":" + result.checkpoint;
        } else {
            visibleDelegate = findBottomSheetDelegate(decorView, true, abi);
            List<View> delegateViews = new ArrayList<>();
            collectBottomSheetViews(decorView, delegateViews, abi);
            for (View view : delegateViews) {
                Object delegate = view.getClass().getField(abi.viewDelegateField).get(view);
                if (delegate != null) attachedDelegates.add(delegate);
            }
        }
        if (visibleDelegate == null) {
            return AttemptResult.waiting(unresolvedCheckpoint);
        }

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
        return rebound
                ? AttemptResult.rebound(abi.id + ":rebound")
                : AttemptResult.waiting(abi.id + ":delegates-attached");
    }

    private static AttemptResult repairCurrentSessionKeyAndDelegate(ClassLoader classLoader,
            Map<Integer, Object> sessions, NavigationAbi abi) throws ReflectiveOperationException {
        if (abi != CURRENT_ABI) return null;

        Object runtime = classLoader.loadClass("X.0F0").getMethod("A0A").invoke(null);
        Object windowManager = runtime.getClass().getField("A0h").get(runtime);
        if (windowManager == null || !"X.0eR".equals(windowManager.getClass().getName())) {
            return null;
        }
        Object windowStack = windowManager.getClass().getField("A0w").get(windowManager);
        Object topScreen = windowStack.getClass().getMethod("A06").invoke(windowStack);
        Class<?> bloksScreenClass = classLoader.loadClass("X.0m5");
        if (topScreen == null || !bloksScreenClass.isInstance(topScreen)) return null;
        int topScreenId = (Integer) topScreen.getClass().getMethod("getScreenId")
                .invoke(topScreen);

        Object rendererManager = windowManager.getClass().getField("A0K").get(windowManager);
        Object flowView = rendererManager == null ? null
                : rendererManager.getClass().getField("A05").get(rendererManager);
        Object stackValue = flowView == null ? null
                : flowView.getClass().getField("A01").get(flowView);
        Object holder = stackValue instanceof Stack && !((Stack<?>) stackValue).isEmpty()
                ? ((Stack<?>) stackValue).peek() : null;
        Object rendererDelegate = null;
        if (holder != null && "X.1ch".equals(holder.getClass().getName())) {
            rendererDelegate = holder.getClass().getField("A00").get(holder);
        }

        logIdDiagnostic(topScreenId, sessions, stackValue, rendererDelegate);
        if (!hasUniqueMismatchedIntegerKey(topScreenId, sessions)
                || !(sessions instanceof ConcurrentMap)
                || rendererDelegate == null
                || !abi.delegateClass.equals(rendererDelegate.getClass().getName())
                || delegateStackSize(rendererDelegate) == 0) {
            return null;
        }

        Map.Entry<Integer, Object> entry = sessions.entrySet().iterator().next();
        Integer oldKey = entry.getKey();
        Object state = entry.getValue();
        if (state == null || !abi.sessionValueClass.equals(state.getClass().getName())) {
            return null;
        }
        Field delegateField = state.getClass().getField("A04");
        if (delegateField.get(state) != null) return null;
        if (!hasOfficialSessionMarker(state)) return null;

        @SuppressWarnings("unchecked")
        ConcurrentMap<Integer, Object> concurrentSessions =
                (ConcurrentMap<Integer, Object>) sessions;
        Integer newKey = topScreenId;
        if (concurrentSessions.putIfAbsent(newKey, state) != null) {
            return AttemptResult.waiting(abi.id + ":session-rekey-raced");
        }
        try {
            delegateField.set(state, rendererDelegate);
            if (!concurrentSessions.remove(oldKey, state)) {
                delegateField.set(state, null);
                concurrentSessions.remove(newKey, state);
                return AttemptResult.waiting(abi.id + ":session-rekey-raced");
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            try {
                delegateField.set(state, null);
            } catch (ReflectiveOperationException ignored) {
                // Preserve the original failure while rolling back the map association below.
            }
            concurrentSessions.remove(newKey, state);
            throw error;
        }
        return AttemptResult.rebound(abi.id + ":session-rekeyed");
    }

    static boolean hasUniqueMismatchedIntegerKey(int topScreenId, Map<?, ?> sessions) {
        if (sessions == null || sessions.size() != 1) return false;
        Object key = sessions.keySet().iterator().next();
        return key instanceof Integer && ((Integer) key) != topScreenId;
    }

    private static boolean hasOfficialSessionMarker(Object state)
            throws ReflectiveOperationException {
        boolean legacyMarker = fieldPresence(state, "A0D") || fieldPresence(state, "A07");
        Object blueprint = state.getClass().getField("A0E").get(state);
        int mode = state.getClass().getField("A00").getInt(state);
        int operation = state.getClass().getField("A01").getInt(state);
        boolean exactPendingReferences = areFieldsNull(state,
                "A02", "A04", "A05", "A06", "A07", "A08", "A0D", "A0E", "A0F");
        return isOfficialSessionMarker(legacyMarker,
                blueprint == null ? null : blueprint.getClass().getName(),
                mode, operation, exactPendingReferences);
    }

    static boolean isOfficialSessionMarker(boolean legacyMarker, String blueprintClass,
            int mode, int operation, boolean exactPendingReferences) {
        return legacyMarker
                || "X.1by".equals(blueprintClass)
                || (mode == 1 && operation == 1 && exactPendingReferences);
    }

    private static boolean areFieldsNull(Object state, String... fieldNames)
            throws ReflectiveOperationException {
        for (String fieldName : fieldNames) {
            if (state.getClass().getField(fieldName).get(state) != null) return false;
        }
        return true;
    }

    private static void logIdDiagnostic(int topScreenId, Map<Integer, Object> sessions,
            Object stackValue, Object rendererDelegate) throws ReflectiveOperationException {
        StringBuilder states = new StringBuilder("[");
        for (Map.Entry<Integer, Object> entry : sessions.entrySet()) {
            if (states.length() > 1) states.append(',');
            Object state = entry.getValue();
            states.append(entry.getKey());
            if (state != null && "X.1HH".equals(state.getClass().getName())) {
                states.append("(a00=").append(state.getClass().getField("A00").getInt(state))
                        .append(",a01=")
                        .append(state.getClass().getField("A01").getInt(state))
                        .append(",a02=").append(fieldClassName(state, "A02"))
                        .append(",a05=").append(fieldClassName(state, "A05"))
                        .append(",a06=").append(fieldClassName(state, "A06"))
                        .append(",a07=").append(fieldClassName(state, "A07"))
                        .append(",a08=").append(fieldClassName(state, "A08"))
                        .append(",a0d=").append(fieldClassName(state, "A0D"))
                        .append(",a0e=").append(fieldClassName(state, "A0E"))
                        .append(",a0f=").append(fieldClassName(state, "A0F"))
                        .append(')');
            }
        }
        states.append(']');
        int stackSize = stackValue instanceof Stack ? ((Stack<?>) stackValue).size() : -1;
        String delegateClass = rendererDelegate == null
                ? "<null>" : rendererDelegate.getClass().getName();
        String diagnostic = "top=" + topScreenId
                + ";map-class=" + sessions.getClass().getName() + ";map=" + states
                + ";renderer-stack=" + stackSize + ";renderer-delegate=" + delegateClass;
        synchronized (FacebookLiteCredentialCompat.class) {
            if (diagnostic.equals(lastIdDiagnostic)) return;
            lastIdDiagnostic = diagnostic;
        }
        VLog.i(TAG, "navigation ids=%s", diagnostic);
    }

    private static String fieldClassName(Object state, String fieldName)
            throws ReflectiveOperationException {
        Object value = state.getClass().getField(fieldName).get(state);
        return value == null ? "<null>" : value.getClass().getName();
    }

    private static AttemptResult runExactNavigationRestore(
            Iterable<Object> sessions, NavigationAbi abi) throws ReflectiveOperationException {
        if (abi.restoreRunnableClass == null) return null;
        Runnable candidate = null;
        for (Object session : sessions) {
            if (session == null || !abi.sessionValueClass.equals(session.getClass().getName())) {
                continue;
            }
            if (session.getClass().getField("A04").get(session) != null) continue;
            Object restore = session.getClass().getField("A0F").get(session);
            if (!(restore instanceof Runnable)
                    || !abi.restoreRunnableClass.equals(restore.getClass().getName())) {
                continue;
            }
            // A newly allocated placeholder session is not safe to restore. Require the same
            // populated session markers Facebook checks before driving navigation.
            if (!hasOfficialSessionMarker(session)) continue;
            if (candidate != null && candidate != restore) {
                return AttemptResult.waiting(abi.id + ":restore-ambiguous");
            }
            candidate = (Runnable) restore;
        }
        if (candidate == null) return null;
        candidate.run();
        return AttemptResult.rebound(abi.id + ":restore-ran");
    }

    private static VisibleDelegateResult findCurrentVisibleDelegate(ClassLoader classLoader,
            View activityDecor, Iterable<Object> sessions, NavigationAbi abi,
            List<Object> attachedDelegates)
            throws ReflectiveOperationException {
        Class<?> hostViewClass = classLoader.loadClass(abi.viewClass);
        List<View> visibleRoots = new ArrayList<>();
        addVisibleRoot(visibleRoots, activityDecor);
        List<Object> sessionDelegates = new ArrayList<>();
        List<Object> visibleCandidates = new ArrayList<>();
        Map<String, Integer> sessionClassCounts = new TreeMap<>();
        Map<String, Integer> delegateClassCounts = new TreeMap<>();
        Map<String, Integer> hostViewClassCounts = new TreeMap<>();
        Map<String, Integer> hostTagClassCounts = new TreeMap<>();
        Map<String, Integer> restoreClassCounts = new TreeMap<>();
        boolean sawStack = false;
        boolean sawEntryView = false;

        for (Object session : sessions) {
            incrementClassCount(sessionClassCounts, session);
            if (session == null) continue;
            if (!abi.sessionValueClass.equals(session.getClass().getName())) continue;
            Object delegate = session.getClass().getField("A04").get(session);
            incrementClassCount(delegateClassCounts, delegate);
            incrementClassCount(restoreClassCounts,
                    session.getClass().getField("A0F").get(session));
            addVisibleDialogRoot(visibleRoots,
                    session.getClass().getField(abi.visibleDialogField).get(session));
            if (delegate == null || !abi.delegateClass.equals(delegate.getClass().getName())) {
                continue;
            }
            addIdentity(sessionDelegates, delegate);
            addVisibleDialogRoot(visibleRoots,
                    delegate.getClass().getField(abi.delegateDialogField).get(delegate));

            Object stackValue = delegate.getClass().getField("A0G").get(delegate);
            if (!(stackValue instanceof Deque)) continue;
            Object entry = ((Deque<?>) stackValue).peek();
            if (entry == null || !abi.navigationEntryClass.equals(entry.getClass().getName())) {
                continue;
            }
            sawStack = true;
            Object entryView = entry.getClass().getField(abi.navigationEntryViewField).get(entry);
            if (!(entryView instanceof View)) continue;
            sawEntryView = true;
            if (isIdentityInVisibleTrees((View) entryView, visibleRoots)) {
                addIdentity(attachedDelegates, delegate);
                addIdentity(visibleCandidates, delegate);
            }
        }

        // RenderCore HostViews store their owning Bloks delegate in X.0kQ.A08. Limit this
        // fallback to visible decor trees and the exact current ABI; never scan arbitrary
        // objects or process memory.
        for (View root : visibleRoots) {
            collectVisibleHostDelegates(root, hostViewClass, abi, sessionDelegates,
                    attachedDelegates, visibleCandidates, hostViewClassCounts,
                    hostTagClassCounts);
        }
        logShapeDiagnostic("sessions=" + sessionClassCounts
                + ";a04=" + delegateClassCounts
                + ";hosts=" + hostViewClassCounts
                + ";host-a08=" + hostTagClassCounts
                + ";a0f=" + restoreClassCounts);

        Object visibleDelegate = uniqueIdentityCandidate(visibleCandidates);
        if (visibleDelegate != null) {
            return VisibleDelegateResult.found(visibleDelegate);
        }
        if (visibleCandidates.size() > 1) {
            return VisibleDelegateResult.waiting("visible-delegate-ambiguous");
        }
        if (sessionDelegates.isEmpty()) {
            return VisibleDelegateResult.waiting("session-delegate-unavailable");
        }
        if (!sawStack) return VisibleDelegateResult.waiting("navigation-stack-unavailable");
        if (!sawEntryView) return VisibleDelegateResult.waiting("entry-view-unavailable");
        if (visibleRoots.isEmpty()) return VisibleDelegateResult.waiting("visible-root-unavailable");
        return VisibleDelegateResult.waiting("visible-host-unavailable");
    }

    private static void addVisibleDialogRoot(List<View> roots, Object value) {
        if (!(value instanceof Dialog)) return;
        Dialog dialog = (Dialog) value;
        if (!dialog.isShowing()) return;
        Window window = dialog.getWindow();
        addVisibleRoot(roots, window == null ? null : window.getDecorView());
    }

    private static void addVisibleRoot(List<View> roots, View root) {
        if (root != null && root.isShown() && !containsIdentity(roots, root)) roots.add(root);
    }

    private static boolean isIdentityInVisibleTrees(View target, List<View> roots) {
        for (View root : roots) {
            if (containsVisibleViewIdentity(root, target)) return true;
        }
        return false;
    }

    private static boolean containsVisibleViewIdentity(View view, View target) {
        if (view == null || !view.isShown()) return false;
        if (view == target) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (containsVisibleViewIdentity(group.getChildAt(index), target)) return true;
        }
        return false;
    }

    private static void collectVisibleHostDelegates(View view, Class<?> hostViewClass,
            NavigationAbi abi, List<Object> sessionDelegates,
            List<Object> attachedDelegates, List<Object> visibleCandidates,
            Map<String, Integer> hostViewClassCounts, Map<String, Integer> hostTagClassCounts)
            throws ReflectiveOperationException {
        if (view == null || !view.isShown()) return;
        if (hostViewClass.isInstance(view)) {
            Object delegate = hostViewClass.getField(abi.viewDelegateField).get(view);
            incrementClassCount(hostViewClassCounts, view);
            incrementClassCount(hostTagClassCounts, delegate);
            if (delegate != null
                    && abi.delegateClass.equals(delegate.getClass().getName())
                    && containsIdentity(sessionDelegates, delegate)) {
                addIdentity(attachedDelegates, delegate);
                if (delegateStackSize(delegate) > 0) addIdentity(visibleCandidates, delegate);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectVisibleHostDelegates(group.getChildAt(index), hostViewClass, abi,
                        sessionDelegates, attachedDelegates, visibleCandidates,
                        hostViewClassCounts, hostTagClassCounts);
            }
        }
    }

    private static void incrementClassCount(Map<String, Integer> counts, Object value) {
        String className = value == null ? "<null>" : value.getClass().getName();
        Integer count = counts.get(className);
        counts.put(className, count == null ? 1 : count + 1);
    }

    private static synchronized void logShapeDiagnostic(String diagnostic) {
        if (diagnostic.equals(lastShapeDiagnostic)) return;
        lastShapeDiagnostic = diagnostic;
        VLog.i(TAG, "navigation shape=%s", diagnostic);
    }

    private static <T> void addIdentity(List<T> values, T value) {
        if (!containsIdentity(values, value)) values.add(value);
    }

    static Object uniqueIdentityCandidate(List<?> candidates) {
        Object candidate = null;
        for (Object value : candidates) {
            if (value == null) continue;
            if (candidate != null && candidate != value) return null;
            candidate = value;
        }
        return candidate;
    }

    private static boolean containsIdentity(List<?> values, Object target) {
        for (Object value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static boolean fieldPresence(Object object, String fieldName)
            throws ReflectiveOperationException {
        return object.getClass().getField(fieldName).get(object) != null;
    }

    private static View currentDecorView(ClassLoader classLoader, NavigationAbi abi)
            throws ReflectiveOperationException {
        Class<?> controllerClass = classLoader.loadClass(abi.controllerClass);
        Object controller = controllerClass.getField("A1Q").get(null);
        Object activity = controller.getClass().getMethod(abi.currentActivityMethod)
                .invoke(controller);
        if (activity == null) return null;
        Window window = (Window) activity.getClass().getMethod("getWindow").invoke(activity);
        return window == null ? null : window.getDecorView();
    }

    private static void collectBottomSheetViews(
            View view, List<View> result, NavigationAbi abi) {
        if (view == null) return;
        if (abi.viewClass.equals(view.getClass().getName())) result.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectBottomSheetViews(group.getChildAt(index), result, abi);
            }
        }
    }

    private static Object findBottomSheetDelegate(
            View view, boolean requireShown, NavigationAbi abi)
            throws ReflectiveOperationException {
        if (view == null || (requireShown && !view.isShown())) return null;
        Object fallback = null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // Later children are drawn above earlier children. Search from the visible top layer.
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                Object delegate = findBottomSheetDelegate(
                        group.getChildAt(index), requireShown, abi);
                if (delegate != null && delegateStackSize(delegate) > 0) return delegate;
                if (fallback == null) fallback = delegate;
            }
        }
        if (abi.viewClass.equals(view.getClass().getName())) {
            Object delegate = view.getClass().getField(abi.viewDelegateField).get(view);
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

    private static NavigationAbi resolveAbi(ClassLoader classLoader, int versionCode)
            throws ReflectiveOperationException {
        NavigationAbi abi = abiForVersion(versionCode);
        if (abi != null && hasExpectedShape(classLoader, abi)) return abi;
        throw new NoSuchFieldException("unsupported Facebook Lite navigation ABI");
    }

    private static NavigationAbi abiForVersion(int versionCode) {
        if (versionCode == 516101866) return LEGACY_ABI;
        if (versionCode == 516201887) return CURRENT_ABI;
        return null;
    }

    private static boolean hasExpectedShape(ClassLoader classLoader, NavigationAbi abi) {
        try {
            Class<?> managerClass = classLoader.loadClass(abi.sessionManagerClass);
            if (managerClass.getField("A04").getType() != managerClass
                    || !Map.class.isAssignableFrom(managerClass.getField("A00").getType())) {
                return false;
            }
            Class<?> controllerClass = classLoader.loadClass(abi.controllerClass);
            if (controllerClass.getField("A1Q").getType() != controllerClass) return false;
            controllerClass.getMethod(abi.currentActivityMethod);
            if (abi.sessionValueClass != null) {
                Class<?> sessionClass = classLoader.loadClass(abi.sessionValueClass);
                Class<?> delegateClass = classLoader.loadClass(abi.delegateClass);
                if (sessionClass.getField("A04").getType() != delegateClass
                        || !Dialog.class.isAssignableFrom(
                                sessionClass.getField(abi.visibleDialogField).getType())
                        || !Deque.class.isAssignableFrom(
                                delegateClass.getField("A0G").getType())) {
                    return false;
                }
                sessionClass.getField("A0D");
                sessionClass.getField("A07");
                if (!Runnable.class.isAssignableFrom(
                        classLoader.loadClass(abi.restoreRunnableClass))) {
                    return false;
                }
                if (!Runnable.class.isAssignableFrom(sessionClass.getField("A0F").getType())) {
                    return false;
                }
                Class<?> entryClass = classLoader.loadClass(abi.navigationEntryClass);
                if (!View.class.isAssignableFrom(
                        entryClass.getField(abi.navigationEntryViewField).getType())
                        || !Dialog.class.isAssignableFrom(
                                delegateClass.getField(abi.delegateDialogField).getType())) {
                    return false;
                }
            }
            if (abi.viewClass != null) {
                Class<?> viewClass = classLoader.loadClass(abi.viewClass);
                if (!View.class.isAssignableFrom(viewClass)) return false;
                viewClass.getField(abi.viewDelegateField);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError error) {
            return false;
        }
    }

    static String abiIdForVersion(int versionCode) {
        NavigationAbi abi = abiForVersion(versionCode);
        return abi == null ? null : abi.id;
    }

    private static final class NavigationAbi {
        final String id;
        final String sessionManagerClass;
        final String sessionValueClass;
        final String delegateClass;
        final String controllerClass;
        final String currentActivityMethod;
        final String viewClass;
        final String viewDelegateField;
        final String visibleDialogField;
        final String navigationEntryClass;
        final String navigationEntryViewField;
        final String delegateDialogField;
        final String restoreRunnableClass;

        NavigationAbi(String id, String sessionManagerClass, String sessionValueClass,
                String delegateClass, String controllerClass, String currentActivityMethod,
                String viewClass, String viewDelegateField, String visibleDialogField,
                String navigationEntryClass, String navigationEntryViewField,
                String delegateDialogField, String restoreRunnableClass) {
            this.id = id;
            this.sessionManagerClass = sessionManagerClass;
            this.sessionValueClass = sessionValueClass;
            this.delegateClass = delegateClass;
            this.controllerClass = controllerClass;
            this.currentActivityMethod = currentActivityMethod;
            this.viewClass = viewClass;
            this.viewDelegateField = viewDelegateField;
            this.visibleDialogField = visibleDialogField;
            this.navigationEntryClass = navigationEntryClass;
            this.navigationEntryViewField = navigationEntryViewField;
            this.delegateDialogField = delegateDialogField;
            this.restoreRunnableClass = restoreRunnableClass;
        }
    }

    private static final class VisibleDelegateResult {
        final Object delegate;
        final String checkpoint;

        private VisibleDelegateResult(Object delegate, String checkpoint) {
            this.delegate = delegate;
            this.checkpoint = checkpoint;
        }

        static VisibleDelegateResult found(Object delegate) {
            return new VisibleDelegateResult(delegate, "visible-delegate-found");
        }

        static VisibleDelegateResult waiting(String checkpoint) {
            return new VisibleDelegateResult(null, checkpoint);
        }
    }

    private static final class AttemptResult {
        final boolean rebound;
        final String checkpoint;

        private AttemptResult(boolean rebound, String checkpoint) {
            this.rebound = rebound;
            this.checkpoint = checkpoint;
        }

        static AttemptResult rebound(String checkpoint) {
            return new AttemptResult(true, checkpoint);
        }

        static AttemptResult waiting(String checkpoint) {
            return new AttemptResult(false, checkpoint);
        }
    }
}
