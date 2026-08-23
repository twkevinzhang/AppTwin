package com.lody.virtual.client.hook.proxies.shortcut;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.PersistableBundle;

import com.lody.virtual.client.env.Constants;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.helper.compat.ParceledListSliceCompat;
import com.lody.virtual.helper.utils.BitmapUtils;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import mirror.android.content.pm.IShortcutService;
import mirror.android.content.pm.ParceledListSlice;

/**
 * @author Lody
 */
public class ShortcutServiceStub extends BinderInvocationProxy {

    private static final String TAG = "ShortcutServiceStub";
    private static final int FALLBACK_ICON_SIZE_PX = 1;

    public ShortcutServiceStub() {
        super(IShortcutService.Stub.asInterface, "shortcut");
    }

    ShortcutServiceStub(BinderInvocationStub invocationStub) {
        super(invocationStub, "shortcut");
    }

    @Override
    public void inject() throws Throwable {
        super.inject();
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplacePkgAndRepairShortcutListMethodProxy("getManifestShortcuts"));
        // TODO: 18/3/3 Support dynamic shortcut ?
        addMethodProxy(new ReplacePkgAndRepairShortcutListMethodProxy("getDynamicShortcuts"));
        addMethodProxy(new ReplacePkgAndRepairShortcutListMethodProxy("getShortcuts"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getShareTargets"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("hasShareTargets"));
        addMethodProxy(new ReplacePkgAndShortcutListMethodProxy("setDynamicShortcuts"));
        addMethodProxy(new ReplacePkgAndShortcutListMethodProxy("addDynamicShortcuts"));
        addMethodProxy(new ReplacePkgAndShortcutListMethodProxy("updateShortcuts"));
        addMethodProxy(new ReplacePkgAndShortcutMethodProxy("pushDynamicShortcut"));
        addMethodProxy(new ReplacePkgAndShortcutMethodProxy("createShortcutResultIntent"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("disableShortcuts"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("enableShortcuts"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("removeDynamicShortcuts"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("removeLongLivedShortcuts"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getRemainingCallCount"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getRateLimitResetTime"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getIconMaxDimensions"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getMaxShortcutCountPerActivity"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("isRequestPinItemSupported"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("reportShortcutUsed"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("onApplicationActive"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("removeAllDynamicShortcuts"));

        addMethodProxy(new ReplacePkgAndRepairShortcutListMethodProxy("getPinnedShortcuts"));
        addMethodProxy(new ReplacePkgAndShortcutMethodProxy("requestPinShortcut"));
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static void replaceShortcutInfo(ShortcutInfo shortcutInfo, String hostPackage, PackageManager pm) {
        if (shortcutInfo == null) {
            return;
        }

        mirror.android.content.pm.ShortcutInfo.mPackageName.set(shortcutInfo, hostPackage);
        rewriteShortcutActivity(
                shortcutInfo,
                hostPackage,
                Constants.SHORTCUT_PROXY_ACTIVITY_NAME,
                ComponentName::new,
                (target, activity) ->
                        mirror.android.content.pm.ShortcutInfo.mActivity.set(target, activity));
        Icon icon = selectHostIcon(
                () -> createBitmapIcon(pm.getApplicationIcon(hostPackage)),
                ShortcutServiceStub::createFallbackIcon,
                (message, error) -> VLog.w(TAG, "%s: %s", message, error));
        // This assignment must be unconditional. After changing the owner package, retaining a
        // resource icon from the guest package makes Android 12's ShortcutService reject the
        // ShortcutInfo with "Icon resource must reside in shortcut owner package".
        mirror.android.content.pm.ShortcutInfo.mIcon.set(shortcutInfo, icon);

        Intent[] intents = mirror.android.content.pm.ShortcutInfo.mIntents.get(shortcutInfo);

        if (intents != null) {
            int length = intents.length;
            Intent[] swap = new Intent[length];

            PersistableBundle[] persistableBundles = mirror.android.content.pm.ShortcutInfo.mIntentPersistableExtrases.get(shortcutInfo);
            if (persistableBundles == null) {
                persistableBundles = new PersistableBundle[length];
            }

            for (int i = 0; i < length; i++) {
                Intent intent = intents[i];
                PersistableBundle persistableBundle = persistableBundles[i];
                if (persistableBundle == null) {
                    persistableBundle = new PersistableBundle();
                }

                Intent shortcutIntent = new Intent(selectShortcutProxyAction(intent.getAction()));
                shortcutIntent.setClassName(hostPackage, Constants.SHORTCUT_PROXY_ACTIVITY_NAME);
                shortcutIntent.addCategory(Intent.CATEGORY_DEFAULT);

                persistableBundle.putString("_VA_|_uri_", intent.toUri(0));
                persistableBundle.putInt("_VA_|_user_id_", 0);
                swap[i] = shortcutIntent;
            }

            System.arraycopy(swap, 0, intents, 0, length);
            mirror.android.content.pm.ShortcutInfo.mIntentPersistableExtrases.set(shortcutInfo, persistableBundles);
        }
    }

    static <S, T> void rewriteShortcutActivity(
            S shortcutInfo,
            String hostPackage,
            String proxyActivityName,
            ShortcutActivityFactory<T> factory,
            ShortcutActivitySetter<S, T> setter) {
        setter.set(shortcutInfo, factory.create(hostPackage, proxyActivityName));
    }

    static String selectShortcutProxyAction(String originalAction) {
        return originalAction == null || originalAction.isEmpty()
                ? Intent.ACTION_VIEW
                : originalAction;
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private static void repairShortcutInfoForGuest(ShortcutInfo shortcutInfo) {
        if (shortcutInfo == null) {
            return;
        }
        Intent[] intents = mirror.android.content.pm.ShortcutInfo.mIntents.get(shortcutInfo);
        if (intents == null) {
            return;
        }
        for (Intent intent : intents) {
            if (intent != null && (intent.getAction() == null || intent.getAction().isEmpty())) {
                // AppTwin versions before this migration published explicit proxy intents without
                // an action. Android accepted them, but ShortcutInfo.Builder rejects them when a
                // guest reads the shortcut back and rebuilds it. Repair the returned copy so the
                // existing launcher state remains usable without deleting user shortcuts.
                intent.setAction(Intent.ACTION_VIEW);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private static boolean isHostNativeShortcut(ShortcutInfo shortcutInfo, String hostPackage) {
        if (shortcutInfo == null) {
            return false;
        }
        String ownerPackage = mirror.android.content.pm.ShortcutInfo.mPackageName.get(shortcutInfo);
        ComponentName activity = mirror.android.content.pm.ShortcutInfo.mActivity.get(shortcutInfo);
        boolean directHostActivity = isDirectHostTarget(activity, hostPackage);
        boolean directHostIntent = false;
        Intent[] intents = mirror.android.content.pm.ShortcutInfo.mIntents.get(shortcutInfo);
        if (intents != null) {
            for (Intent intent : intents) {
                if (isDirectHostTarget(intent, hostPackage)) {
                    directHostIntent = true;
                    break;
                }
            }
        }
        return isHostNativeShortcutOwnership(
                ownerPackage,
                hostPackage,
                directHostActivity,
                directHostIntent);
    }

    private static boolean isDirectHostTarget(ComponentName component, String hostPackage) {
        return component != null
                && hostPackage.equals(component.getPackageName())
                && !Constants.SHORTCUT_PROXY_ACTIVITY_NAME.equals(component.getClassName());
    }

    private static boolean isDirectHostTarget(Intent intent, String hostPackage) {
        if (intent == null) {
            return false;
        }
        ComponentName component = intent.getComponent();
        if (component != null) {
            return isDirectHostTarget(component, hostPackage);
        }
        return hostPackage.equals(intent.getPackage());
    }

    static boolean isHostNativeShortcutOwnership(
            String ownerPackage,
            String hostPackage,
            boolean directHostActivity,
            boolean directHostIntent) {
        return hostPackage != null
                && hostPackage.equals(ownerPackage)
                && (directHostActivity || directHostIntent);
    }

    static <T> List<T> filterAndTransformGuestShortcuts(
            List<T> shortcuts,
            HostNativeShortcutPredicate<T> hostNativePredicate,
            GuestShortcutTransformer<T> guestTransformer) {
        List<T> guestShortcuts = new ArrayList<>();
        if (shortcuts == null) {
            return guestShortcuts;
        }
        for (T shortcut : shortcuts) {
            if (transformGuestShortcut(shortcut, hostNativePredicate, guestTransformer)) {
                guestShortcuts.add(shortcut);
            }
        }
        return guestShortcuts;
    }

    static <T> boolean transformGuestShortcut(
            T shortcut,
            HostNativeShortcutPredicate<T> hostNativePredicate,
            GuestShortcutTransformer<T> guestTransformer) {
        if (hostNativePredicate.isHostNative(shortcut)) {
            return false;
        }
        guestTransformer.transform(shortcut);
        return true;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static Icon createBitmapIcon(Drawable drawable) {
        Bitmap bitmap = BitmapUtils.drawableToBitmap(drawable);
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            throw new IllegalArgumentException("Host application icon could not be rendered");
        }
        return Icon.createWithBitmap(bitmap);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static Icon createFallbackIcon() {
        Bitmap bitmap = Bitmap.createBitmap(
                FALLBACK_ICON_SIZE_PX,
                FALLBACK_ICON_SIZE_PX,
                Bitmap.Config.ARGB_8888);
        return Icon.createWithBitmap(bitmap);
    }

    static <T> T selectHostIcon(IconProvider<T> applicationIcon, IconProvider<T> fallbackIcon) {
        return selectHostIcon(applicationIcon, fallbackIcon, (message, error) -> { });
    }

    private static <T> T selectHostIcon(IconProvider<T> applicationIcon,
                                        IconProvider<T> fallbackIcon,
                                        IconFailureReporter failureReporter) {
        try {
            T icon = applicationIcon.get();
            if (icon != null) {
                return icon;
            }
            failureReporter.report(
                    "Host application icon provider returned null; using fallback icon",
                    new IllegalStateException("null host icon"));
        } catch (Throwable error) {
            failureReporter.report(
                    "Unable to render host application icon; using fallback icon", error);
        }

        try {
            return fallbackIcon.get();
        } catch (Throwable error) {
            // Returning null is intentional: an icon-less shortcut is valid, while retaining the
            // guest's resource icon after rewriting mPackageName is fatal on Android 12.
            failureReporter.report("Unable to create fallback shortcut icon; removing icon", error);
            return null;
        }
    }

    interface IconProvider<T> {
        T get() throws Throwable;
    }

    interface ShortcutActivityFactory<T> {
        T create(String packageName, String className);
    }

    interface ShortcutActivitySetter<S, T> {
        void set(S shortcutInfo, T activity);
    }

    interface HostNativeShortcutPredicate<T> {
        boolean isHostNative(T shortcut);
    }

    interface GuestShortcutTransformer<T> {
        void transform(T shortcut);
    }

    private interface IconFailureReporter {
        void report(String message, Throwable error);
    }

    private static class ReplacePkgAndShortcutListMethodProxy extends ReplaceCallingPkgMethodProxy {
        ReplacePkgAndShortcutListMethodProxy(String name) {
            super(name);
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {

            List<ShortcutInfo> shortcutList = findFirstShortcutList(args);
            if (shortcutList != null) {
                String hostPkg = getHostPkg();
                List<ShortcutInfo> guestShortcuts = filterAndTransformGuestShortcuts(
                        shortcutList,
                        shortcut -> isHostNativeShortcut(shortcut, hostPkg),
                        shortcut -> replaceShortcutInfo(shortcut, hostPkg, getPM()));
                replaceFirstShortcutList(args, guestShortcuts);
            }

            return super.beforeCall(who, method, args);
        }

        @TargetApi(Build.VERSION_CODES.N_MR1)
        private List<ShortcutInfo> findFirstShortcutList(Object... args) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
                if (arg != null && ParceledListSlice.TYPE != null
                        && ParceledListSlice.TYPE.isAssignableFrom(arg.getClass())) {
                    return ParceledListSliceCompat.getList(arg);
                }
            }
            return null;
        }

        private void replaceFirstShortcutList(Object[] args, List<ShortcutInfo> shortcuts) {
            if (args == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg != null && ParceledListSlice.TYPE != null
                        && ParceledListSlice.TYPE.isAssignableFrom(arg.getClass())) {
                    args[i] = ParceledListSliceCompat.create(shortcuts);
                    return;
                }
            }
        }
    }

    private static class ReplacePkgAndRepairShortcutListMethodProxy
            extends ReplaceCallingPkgMethodProxy {
        ReplacePkgAndRepairShortcutListMethodProxy(String name) {
            super(name);
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result)
                throws Throwable {
            Object replacement = super.afterCall(who, method, args, result);
            if (replacement == null) {
                return null;
            }
            List shortcutList;
            if (replacement instanceof List) {
                shortcutList = (List) replacement;
            } else {
                shortcutList = ParceledListSliceCompat.getList(replacement);
            }
            String hostPkg = getHostPkg();
            List guestShortcuts = filterAndTransformGuestShortcuts(
                    shortcutList,
                    item -> item instanceof ShortcutInfo
                            && isHostNativeShortcut((ShortcutInfo) item, hostPkg),
                    item -> {
                        if (item instanceof ShortcutInfo) {
                            repairShortcutInfoForGuest((ShortcutInfo) item);
                        }
                    });
            if (replacement instanceof List) {
                return guestShortcuts;
            }
            return ParceledListSliceCompat.createForReturnType(method, guestShortcuts);
        }
    }

    private static class ReplacePkgAndShortcutMethodProxy extends ReplaceCallingPkgMethodProxy {

        ReplacePkgAndShortcutMethodProxy(String name) {
            super(name);
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            ShortcutInfo shortcutInfo = findFirstShortcutInfo(args);
            String hostPkg = getHostPkg();
            transformGuestShortcut(
                    shortcutInfo,
                    shortcut -> isHostNativeShortcut(shortcut, hostPkg),
                    shortcut -> replaceShortcutInfo(shortcut, hostPkg, getPM()));

            return super.beforeCall(who, method, args);
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ShortcutInfo shortcutInfo = findFirstShortcutInfo(args);
            if (isHostNativeShortcut(shortcutInfo, getHostPkg())) {
                return protectedHostWriteResult(method.getReturnType());
            }
            return super.call(who, method, args);
        }

        @TargetApi(Build.VERSION_CODES.N_MR1)
        private ShortcutInfo findFirstShortcutInfo(Object[] args) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
                if (arg != null && arg.getClass() == mirror.android.content.pm.ShortcutInfo.TYPE) {
                    return (ShortcutInfo) arg;
                }
            }
            return null;
        }

        private Object protectedHostWriteResult(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0F;
            }
            if (returnType == double.class) {
                return 0D;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == char.class) {
                return (char) 0;
            }
            return null;
        }
    }
}
