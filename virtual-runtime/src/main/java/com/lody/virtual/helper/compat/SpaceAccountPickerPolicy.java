package com.lody.virtual.helper.compat;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.stub.ChooseTypeAndAccountActivity;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;

/** Exact framework account chooser routing and caller-owned virtual-user scope. */
public final class SpaceAccountPickerPolicy {
    private static final String PREFIX = "apptwin.accountPicker.";
    private static final String SOURCE = PREFIX + "source";
    private static final String CALLER = PREFIX + "caller";
    private static final String SERIAL = PREFIX + "serial";
    private static final String EPOCH = PREFIX + "epoch";
    private static final String PACKAGE_EPOCH = PREFIX + "packageEpoch";

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
        String caller = VClientImpl.get().getCurrentPackage();
        VUserInfo user = VUserManager.get().getUserInfo(userId);
        if (caller == null || user == null) return null;
        Intent bridge = new Intent(request);
        bridge.setSelector(null);
        bridge.setPackage(null);
        bridge.setComponent(new ComponentName(hostPackage, ChooseTypeAndAccountActivity.class.getName()));
        Bundle scope = new Bundle();
        scope.putInt(ChooseTypeAndAccountActivity.KEY_USER_ID, userId);
        scope.putBinder(SOURCE, source);
        scope.putString(CALLER, caller);
        scope.putInt(SERIAL, user.serialNumber);
        scope.putLong(EPOCH, user.pendingIntentGeneration);
        scope.putLong(PACKAGE_EPOCH, VUserManager.get().getPackagePendingIntentGeneration(caller, userId));
        bridge.putExtras(scope);
        return bridge;
    }

    public static void copyScope(Intent source, Intent destination) {
        Bundle data = source.getExtras();
        if (data == null) return;
        Bundle scope = new Bundle();
        scope.putInt(ChooseTypeAndAccountActivity.KEY_USER_ID,
                data.getInt(ChooseTypeAndAccountActivity.KEY_USER_ID, -1));
        scope.putBinder(SOURCE, data.getBinder(SOURCE));
        scope.putString(CALLER, data.getString(CALLER));
        scope.putInt(SERIAL, data.getInt(SERIAL, -1));
        scope.putLong(EPOCH, data.getLong(EPOCH, 0));
        scope.putLong(PACKAGE_EPOCH, data.getLong(PACKAGE_EPOCH, 0));
        destination.putExtras(scope);
    }

    public static int validatedUser(Intent intent) {
        try {
            Bundle data = intent.getExtras();
            if (data == null || !data.containsKey(SERIAL) || !data.containsKey(EPOCH)
                    || !data.containsKey(PACKAGE_EPOCH)) return -1;
            int userId = data.getInt(ChooseTypeAndAccountActivity.KEY_USER_ID, -1);
            String caller = data.getString(CALLER);
            IBinder source = data.getBinder(SOURCE);
            VUserInfo user = VUserManager.get().getUserInfo(userId);
            if (user == null || caller == null || source == null
                    || !matchesGeneration(data.getInt(SERIAL, -1), data.getLong(EPOCH, 0),
                    data.getLong(PACKAGE_EPOCH, 0), user.serialNumber, user.pendingIntentGeneration,
                    VUserManager.get().getPackagePendingIntentGeneration(caller, userId))) return -1;
            String authoritative = VActivityManager.get().getService().getPackageForToken(userId, source);
            return caller.equals(authoritative) ? userId : -1;
        } catch (Exception | LinkageError ignored) { return -1; }
    }

    public static boolean matchesGeneration(int serial, long epoch, long packageEpoch,
            int currentSerial, long currentEpoch, long currentPackageEpoch) {
        return serial >= 0 && serial == currentSerial && epoch == currentEpoch
                && packageEpoch != 0 && packageEpoch == currentPackageEpoch;
    }
}
