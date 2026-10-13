package com.lody.virtual.client.hook.providers;

import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.fixer.GuestAudioIdentityDiagnostics;
import com.lody.virtual.client.hook.base.MethodBox;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import mirror.android.content.IContentProvider;

/**
 * @author Lody
 */

public class ProviderHook implements InvocationHandler {

    public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";

    public static final String QUERY_ARG_SQL_SELECTION_ARGS =
            "android:query-arg-sql-selection-args";
    public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";


    private static final Map<String, HookFetcher> PROVIDER_MAP = new HashMap<>();

    static {
        PROVIDER_MAP.put("settings", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new SettingsProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("downloads", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new DownloadProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("media", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new MediaProviderHook(provider);
            }
        });
    }

    protected final Object mBase;
    private String permissionAuthority;

    public ProviderHook(Object base) {
        this.mBase = base;
    }

    private static HookFetcher fetchHook(String authority) {
        HookFetcher fetcher = PROVIDER_MAP.get(authority);
        if (fetcher == null) {
            fetcher = new HookFetcher() {
                @Override
                public ProviderHook fetch(boolean external, IInterface provider) {
                    if (external) {
                        return new ExternalProviderHook(provider);
                    }
                    return new InternalProviderHook(provider);
                }
            };
        }
        return fetcher;
    }

    private static IInterface createProxy(IInterface provider, ProviderHook hook) {
        if (provider == null || hook == null) {
            return null;
        }
        return (IInterface) Proxy.newProxyInstance(provider.getClass().getClassLoader(), new Class[]{
                IContentProvider.TYPE,
        }, hook);
    }

    public static IInterface createProxy(boolean external, String authority, IInterface provider) {
        if (provider instanceof Proxy && Proxy.getInvocationHandler(provider) instanceof ProviderHook) {
            return provider;
        }
        ProviderHook.HookFetcher fetcher = ProviderHook.fetchHook(authority);
        if (fetcher != null) {
            ProviderHook hook = fetcher.fetch(external, provider);
            hook.permissionAuthority = authority;
            IInterface proxyProvider = ProviderHook.createProxy(provider, hook);
            if (proxyProvider != null) {
                provider = proxyProvider;
            }
        }
        return provider;
    }

    private void enforceSpaceAccess(String method, Object[] args) {
        if ("asBinder".equals(method)) return;
        String authority = ProviderAuthorityPolicy.normalize(permissionAuthority);
        if (authority == null) return;
        // Never compare ProviderInfo.authority as a single string: ContactsProvider commonly
        // publishes com.android.contacts;contacts. Gate aliases and URI-scoped acquisition.
        String operationAuthority = null;
        if (args != null) for (Object arg : args) if (arg instanceof Uri) {
            operationAuthority = ProviderAuthorityPolicy.normalize(((Uri) arg).getAuthority());
            break;
        }
        if (ProviderAuthorityPolicy.contacts(authority) || ProviderAuthorityPolicy.contacts(operationAuthority)) {
            boolean write = "insert".equals(method) || "bulkInsert".equals(method)
                    || "delete".equals(method) || "update".equals(method);
            if ("openFile".equals(method) || "openAssetFile".equals(method)) {
                boolean afterUri = false;
                if (args != null) for (Object arg : args) {
                    if (arg instanceof Uri) { afterUri = true; continue; }
                    if (afterUri && arg instanceof String) {
                        write = ((String) arg).contains("w");
                        break;
                    }
                }
            }
            com.lody.virtual.client.hook.GuestPermissionPolicy.require(write
                    ? "android.permission.WRITE_CONTACTS" : "android.permission.READ_CONTACTS");
            // Unknown call/batch operations can perform both reads and writes.
            if ("call".equals(method) || "applyBatch".equals(method)) {
                com.lody.virtual.client.hook.GuestPermissionPolicy.require("android.permission.WRITE_CONTACTS");
            }
        }
        if (authority.endsWith(".documents") || authority.contains("photopicker")) {
            Uri selected = null;
            String documentMode = null;
            boolean afterUri = false;
            if (args != null) for (Object arg : args) {
                if (arg instanceof Uri) { selected = (Uri) arg; afterUri = true; continue; }
                if (afterUri && arg instanceof String && documentMode == null) documentMode = (String) arg;
            }
            if (!com.lody.virtual.client.hook.GuestSelectedUriGrants.hasRead(selected)) {
                com.lody.virtual.helper.compat.FilePickerDiagnostics.denied(method, selected != null);
                throw new SecurityException("URI was not selected for this space");
            }
            if (MediaProviderAccessPolicy.usesLegacyWriteCapability(Build.VERSION.SDK_INT)
                    && MediaProviderAccessPolicy.requiresWrite(method, documentMode)) {
                com.lody.virtual.client.hook.GuestPermissionPolicy.require("android.permission.WRITE_EXTERNAL_STORAGE");
                if (selected == null || VirtualCore.get().getContext().checkUriPermission(selected,
                        android.os.Process.myPid(), android.os.Process.myUid(),
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Selected URI has no write grant for this space");
                }
            }
        }
        if (ProviderAuthorityPolicy.contains(authority, "media") || "media".equals(operationAuthority)) {
            Uri uri = null;
            if (args != null) for (Object arg : args) if (arg instanceof Uri) { uri = (Uri) arg; break; }
            String mode = null;
            if ("openFile".equals(method) || "openAssetFile".equals(method)) {
                boolean afterUri = false;
                if (args != null) for (Object arg : args) {
                    if (arg instanceof Uri) { afterUri = true; continue; }
                    if (afterUri && arg instanceof String) { mode = (String) arg; break; }
                }
            }
            boolean selectedReadGranted = com.lody.virtual.client.hook.GuestSelectedUriGrants.hasRead(uri)
                    && VirtualCore.get().getContext().checkUriPermission(uri,
                    android.os.Process.myPid(), android.os.Process.myUid(),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            String path = uri == null ? "" : uri.getPath();
            boolean onlyImages = path != null && path.contains("/images");
            boolean onlyVideos = path != null && path.contains("/video");
            // Preserve the existing audio read behavior; audio mutations still need WRITE.
            boolean readGranted = path != null && path.contains("/audio")
                    || com.lody.virtual.client.hook.GuestPermissionPolicy.canReadMedia(
                    !onlyVideos, !onlyImages);
            if (!MediaProviderAccessPolicy.usesLegacyWriteCapability(Build.VERSION.SDK_INT)) {
                // Preserve modern scoped-storage behavior; the platform still checks the operation.
                if (selectedReadGranted || readGranted) return;
                throw new SecurityException("Space media permission denied");
            }
            boolean writeGranted = com.lody.virtual.client.hook.GuestPermissionPolicy.isGranted(
                    "android.permission.WRITE_EXTERNAL_STORAGE");
            if (!MediaProviderAccessPolicy.allows(method, mode, readGranted, writeGranted, selectedReadGranted)) {
                throw new SecurityException("Space media permission denied");
            }
        }
    }

    public Bundle call(MethodBox methodBox, String method, String arg, Bundle extras) throws InvocationTargetException {
        return methodBox.call();
    }

    public Uri insert(MethodBox methodBox, Uri url, ContentValues initialValues) throws InvocationTargetException {

        return (Uri) methodBox.call();
    }

    public Cursor query(MethodBox methodBox, Uri url, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder, Bundle originQueryArgs) throws InvocationTargetException {
        return (Cursor) methodBox.call();
    }

    public String getType(MethodBox methodBox, Uri url) throws InvocationTargetException {
        return (String) methodBox.call();
    }

    public int bulkInsert(MethodBox methodBox, Uri url, ContentValues[] initialValues) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public int delete(MethodBox methodBox, Uri url, String selection, String[] selectionArgs) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public int update(MethodBox methodBox, Uri url, ContentValues values, String selection,
                      String[] selectionArgs) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public ParcelFileDescriptor openFile(MethodBox methodBox, Uri url, String mode) throws InvocationTargetException {
        return (ParcelFileDescriptor) methodBox.call();
    }

    public AssetFileDescriptor openAssetFile(MethodBox methodBox, Uri url, String mode) throws InvocationTargetException {
        return (AssetFileDescriptor) methodBox.call();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object... args) throws Throwable {
        enforceSpaceAccess(method.getName(), args);
        try {
            processArgs(method, args);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        MethodBox methodBox = new MethodBox(method, mBase, args);
        int start = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ? 1 : 0;
        if (BuildCompat.isS()) {
            // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r16:frameworks/base/core/java/android/content/IContentProvider.java
            start = 1;
        } else if (BuildCompat.isR()) {
            // Android 11: https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/IContentProvider.java?q=IContentProvider&ss=android%2Fplatform%2Fsuperproject
            start = 2;
        }
        if (BuildCompat.isS() ) {
            tryFixAttributionSource(args);
        }
        try {
            String name = method.getName();
            if ("call".equals(name)) {
                if (BuildCompat.isS()) {
                    // What's the fuck with Google???
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r1:frameworks/base/core/java/android/content/IContentProvider.java;l=123
                    start = 2;
                } else if (BuildCompat.isR()) {
                    start = 3;
                } else if (BuildCompat.isQ()) {
                    start = 2;
                }
                String methodName = (String) args[start];
                String arg = (String) args[start + 1];
                Bundle extras = (Bundle) args[start + 2];
                return call(methodBox, methodName, arg, extras);
            } else if ("insert".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues initialValues = (ContentValues) args[start + 1];
                return insert(methodBox, url, initialValues);
            } else if ("getType".equals(name)) {
                return getType(methodBox, (Uri) args[0]);
            } else if ("delete".equals(name)) {
                Uri url = (Uri) args[start];
                String selection = optionalStringArg(args, start + 1);
                String[] selectionArgs = optionalStringArrayArg(args, start + 2);
                return delete(methodBox, url, selection, selectionArgs);
            } else if ("bulkInsert".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues[] initialValues = (ContentValues[]) args[start + 1];
                return bulkInsert(methodBox, url, initialValues);
            } else if ("update".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues values = (ContentValues) args[start + 1];
                // Android 11+ replaces the legacy selection/selectionArgs pair with a
                // single Bundle. Android 17 therefore supplies only four arguments:
                // AttributionSource, Uri, ContentValues and Bundle. Keep exposing the
                // legacy values to specialised hooks when present, but never index past
                // the modern method shape before forwarding the original invocation.
                String selection = optionalStringArg(args, start + 2);
                String[] selectionArgs = optionalStringArrayArg(args, start + 3);
                return update(methodBox, url, values, selection, selectionArgs);
            } else if ("openFile".equals(name)) {
                Uri url = (Uri) args[start];
                String mode = (String) args[start + 1];
                return openFile(methodBox, url, mode);
            } else if ("openAssetFile".equals(name)) {
                Uri url = (Uri) args[start];
                String mode = (String) args[start + 1];
                return openAssetFile(methodBox, url, mode);
            } else if ("query".equals(name)) {
                Uri url = (Uri) args[start];
                String[] projection = (String[]) args[start + 1];
                String selection = null;
                String[] selectionArgs = null;
                String sortOrder = null;
                Bundle queryArgs = null;

                if (Build.VERSION.SDK_INT >= 26) {
                    queryArgs = (Bundle) args[start + 2];
                    if (queryArgs != null) {
                        selection = queryArgs.getString(QUERY_ARG_SQL_SELECTION);
                        selectionArgs = queryArgs.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);
                        sortOrder = queryArgs.getString(QUERY_ARG_SQL_SORT_ORDER);
                    }
                } else {
                    selection = (String) args[start + 2];
                    selectionArgs = (String[]) args[start + 3];
                    sortOrder = (String) args[start + 4];
                }
                return query(methodBox, url, projection, selection, selectionArgs, sortOrder, queryArgs);
            }
            return methodBox.call();
        } catch (Throwable e) {
            VLog.d("ProviderHook", "call: %s (%s) with error", method.getName(), Arrays.toString(args));
            if (e instanceof InvocationTargetException) {
                throw e.getCause();
            }
            throw e;
        }
    }

    protected void processArgs(Method method, Object... args) {

    }

    static String optionalStringArg(Object[] args, int index) {
        return args != null && index >= 0 && index < args.length && args[index] instanceof String
                ? (String) args[index]
                : null;
    }

    static String[] optionalStringArrayArg(Object[] args, int index) {
        return args != null && index >= 0 && index < args.length && args[index] instanceof String[]
                ? (String[]) args[index]
                : null;
    }

    protected boolean isExternalProvider() {
        return false;
    }

    public interface HookFetcher {
        ProviderHook fetch(boolean external, IInterface provider);
    }

    private void tryFixAttributionSource(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        Object attribution = args[0];
        if (attribution == null) {
            return;
        }
        if (!attribution.getClass().getName().equals("android.content.AttributionSource")) {
            return;
        }

        ApplicationInfo currentApplication = VClientImpl.get().getCurrentApplicationInfo();
        String currentPackage = currentApplication == null
                ? null : currentApplication.packageName;
        String packageName = ProviderAttributionIdentityPolicy.packageName(
                isExternalProvider(), currentPackage, VirtualCore.get().getHostPkg());
        Object callAttribution = ProviderAttributionCallCopy.copyForCall(attribution,
                currentPackage, VirtualCore.get().getHostPkg(), VClientImpl.get().getVUid(),
                VirtualCore.get().myUid(), packageName);
        args[0] = callAttribution;
        GuestAudioIdentityDiagnostics.providerCallCopy(attribution, callAttribution);
    }
}
