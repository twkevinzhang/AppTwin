package com.lody.virtual.client.fixer;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.DropBoxManager;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.proxies.dropbox.DropBoxManagerStub;
import com.lody.virtual.client.hook.proxies.graphics.GraphicsStatsStub;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.ReflectException;

import mirror.android.app.ContextImpl;
import mirror.android.app.ContextImplKitkat;
import mirror.android.content.ContentResolverJBMR2;

/**
 * @author Lody
 */
public class ContextFixer {

    private static final String TAG = ContextFixer.class.getSimpleName();

    /**
     * Fuck AppOps
     *
     * @param context Context
     */
    public static void fixContext(Context context) {
        GuestAudioIdentityDiagnostics.snapshot("fix-context-before", context);
        try {
            context.getPackageName();
        } catch (Throwable e) {
            return;
        }
        InvocationStubManager.getInstance().checkEnv(GraphicsStatsStub.class);
        int deep = 0;
        while (context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
            deep++;
            if (deep >= 10) {
                return;
            }
        }
        ContextImpl.mPackageManager.set(context, null);
        try {
            context.getPackageManager();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if (!VirtualCore.get().isVAppProcess()) {
            return;
        }
        DropBoxManager dm = (DropBoxManager) context.getSystemService(Context.DROPBOX_SERVICE);
        BinderInvocationStub boxBinder = InvocationStubManager.getInstance().getInvocationStub(DropBoxManagerStub.class);
        if (boxBinder != null) {
            try {
                Reflect.on(dm).set("mService", boxBinder.getProxyInterface());
            } catch (ReflectException e) {
                e.printStackTrace();
            }
        }
        String hostPkg = VirtualCore.get().getHostPkg();
        ApplicationInfo currentApplication = VClientImpl.get().getCurrentApplicationInfo();
        String currentPackage = currentApplication == null
                ? null : currentApplication.packageName;
        // Context.getPackageName() is guest-facing identity. Google client SDKs include it in
        // requests to GmsCore, where microG validates it against the virtual Binder caller. Keep
        // only the operation/attribution identities on the physical host package for framework
        // AppOps and provider UID validation.
        ContextImpl.mBasePackageName.set(
                context, guestBasePackageName(currentPackage, hostPkg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            ContextImplKitkat.mOpPackageName.set(context, hostPkg);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            ContentResolverJBMR2.mPackageName.set(context.getContentResolver(), hostPkg);
        }

        if (ContextImpl.getAttributionSource != null) {
            // Android 12 validates AttributionSource.uid against Binder.getCallingUid() before a
            // ContentProvider call reaches our provider hook. The Binder caller is the host app,
            // not the virtual guest UID, so retaining the guest UID here makes otherwise valid
            // provider queries fail with "Calling uid doesn't match source uid".
            fixAttributionSource(ContextImpl.getAttributionSource.call(context), hostPkg, VirtualCore.get().myUid());
        }
        GuestAudioIdentityDiagnostics.snapshot("fix-context-after", context);
    }

    static String guestBasePackageName(String currentPackage, String hostPackage) {
        return currentPackage == null || currentPackage.length() == 0
                ? hostPackage : currentPackage;
    }

    public static void fixAttributionSource(Object attr, String pkg, int uid) {
        if (attr == null) {
            return;
        }
        try {
            Object mAttributionSourceState = Reflect.on(attr).get("mAttributionSourceState");
            Reflect.on(mAttributionSourceState).set("uid", uid);
            Reflect.on(mAttributionSourceState).set("packageName", pkg);

            Object next = Reflect.on(attr).call("getNext").get();
            fixAttributionSource(next, pkg, uid);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

}
