package com.lody.virtual.helper.compat;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.stub.ChooseTypeAndAccountActivity;

/** Exact framework account chooser routing and caller-owned virtual-user scope. */
public final class SpaceAccountPickerPolicy {
    private static final String SESSION = "apptwin.accountPicker.session";

    private SpaceAccountPickerPolicy() { }

    public static boolean isFrameworkComponent(String packageName, String className) {
        return "android".equals(packageName)
                && "android.accounts.ChooseTypeAndAccountActivity".equals(className);
    }

    public static Intent effectiveRequest(Intent intent) {
        if (intent == null) return null;
        ComponentName component = intent.getComponent();
        if (component != null) return isFrameworkComponent(component.getPackageName(), component.getClassName())
                ? intent : null;
        Intent selector = intent.getSelector();
        component = selector == null ? null : selector.getComponent();
        return component != null && isFrameworkComponent(component.getPackageName(), component.getClassName())
                ? selector : null;
    }

    public static Intent redirect(Intent request, String hostPackage, int userId, IBinder source) {
        if (source == null || userId < 0) return null;
        try {
            String session = VActivityManager.get().getService().prepareNativeAccountPicker(source);
            if (session == null) return null;
            Intent bridge = new Intent(request);
            bridge.setSelector(null);
            bridge.setPackage(null);
            bridge.setComponent(new ComponentName(hostPackage, ChooseTypeAndAccountActivity.class.getName()));
            bridge.putExtra(ChooseTypeAndAccountActivity.KEY_USER_ID, userId);
            bridge.putExtra(SESSION, session);
            return bridge;
        } catch (Exception | LinkageError ignored) { return null; }
    }

    public static void copyScope(Intent source, Intent destination) {
        destination.putExtra(SESSION, source.getStringExtra(SESSION));
        destination.putExtra(ChooseTypeAndAccountActivity.KEY_USER_ID,
                source.getIntExtra(ChooseTypeAndAccountActivity.KEY_USER_ID, -1));
    }

    public static int claimUser(Intent intent) {
        try {
            int user = VActivityManager.get().getService().claimNativeAccountPicker(intent.getStringExtra(SESSION));
            traceScope("claim", user >= 0);
            return user;
        } catch (Exception | LinkageError ignored) { traceScope("claim", false); return -1; }
    }
    public static int validatedUser(Intent intent) {
        try {
            int user = VActivityManager.get().getService().currentNativeAccountPicker(intent.getStringExtra(SESSION));
            traceScope("current", user >= 0);
            return user;
        } catch (Exception | LinkageError ignored) { traceScope("current", false); return -1; }
    }
    public static boolean complete(Intent intent) {
        try {
            boolean accepted = VActivityManager.get().getService().completeNativeAccountPicker(intent.getStringExtra(SESSION));
            traceScope("complete", accepted);
            return accepted;
        } catch (Exception | LinkageError ignored) { traceScope("complete", false); return false; }
    }
    private static void traceScope(String stage, boolean accepted) {
        try {
            com.lody.virtual.helper.utils.VLog.i("SpaceAccountPicker", "checkpoint=scope-%s accepted=%s", stage, accepted);
        } catch (RuntimeException | LinkageError unavailableDiagnostics) { }
    }
}
