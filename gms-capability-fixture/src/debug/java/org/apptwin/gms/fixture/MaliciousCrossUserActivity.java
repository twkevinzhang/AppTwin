package org.apptwin.gms.fixture;

import android.accounts.Account;
import android.app.Activity;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;

/**
 * Deliberately bypasses Android framework hooks and calls AppTwin's virtual Binder clients with a
 * forged target user id. Results contain booleans only; account values and tokens are never saved.
 */
public final class MaliciousCrossUserActivity extends Activity {
    public static final String RESULT_FILE = "cross_user_probe";
    public static final String REQUEST_FILE = "cross_user_request";
    public static final String COMPLETE = "complete";
    public static final String EXTRA_TARGET_USER = "target_user";
    public static final String EXTRA_SELF_USER = "self_user";
    public static final String EXTRA_ACCOUNT_TYPE = "account_type";
    public static final String EXTRA_TARGET_ACCOUNT = "target_account";
    public static final String EXTRA_SELF_ACCOUNT = "self_account";
    public static final String TOKEN_TYPE = "apptwin-malicious-e2e";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            sRuntimeLoader = createPackageContext(
                    "org.apptwin", CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY).getClassLoader();
        } catch (Exception unavailable) {
            throw new IllegalStateException("Host runtime class loader is unavailable", unavailable);
        }
        SharedPreferences request = getSharedPreferences(REQUEST_FILE, MODE_PRIVATE);
        int targetUser = getIntent().getIntExtra(
                EXTRA_TARGET_USER, request.getInt(EXTRA_TARGET_USER, -1));
        int selfUser = getIntent().getIntExtra(
                EXTRA_SELF_USER, request.getInt(EXTRA_SELF_USER, -1));
        String accountType = getIntent().getStringExtra(EXTRA_ACCOUNT_TYPE);
        if (accountType == null) accountType = request.getString(EXTRA_ACCOUNT_TYPE, null);
        String targetName = getIntent().getStringExtra(EXTRA_TARGET_ACCOUNT);
        if (targetName == null) targetName = request.getString(EXTRA_TARGET_ACCOUNT, null);
        String selfName = getIntent().getStringExtra(EXTRA_SELF_ACCOUNT);
        if (selfName == null) selfName = request.getString(EXTRA_SELF_ACCOUNT, null);
        final String requestedAccountType = accountType;
        Account targetAccount = new Account(
                targetName, requestedAccountType);
        Account selfAccount = new Account(
                selfName, requestedAccountType);

        SharedPreferences.Editor result = getSharedPreferences(RESULT_FILE, MODE_PRIVATE).edit();
        result.putBoolean("cross_account_list_blocked", blockedOrEmpty(() ->
                callAccount("getAccounts", new Class<?>[]{int.class, String.class},
                        targetUser, requestedAccountType)));
        result.putBoolean("cross_password_blocked", blockedOrNull(() ->
                callAccount("getPassword", new Class<?>[]{int.class, Account.class},
                        targetUser, targetAccount)));
        result.putBoolean("cross_token_blocked", blockedOrNull(() ->
                callAccount("peekAuthToken",
                        new Class<?>[]{int.class, Account.class, String.class},
                        targetUser, targetAccount, TOKEN_TYPE)));
        result.putBoolean("cross_password_write_blocked", blocked(() -> {
            callAccount("setPassword", new Class<?>[]{int.class, Account.class, String.class},
                    targetUser, targetAccount, "rejected-cross-user-password");
            return null;
        }));
        result.putBoolean("cross_token_write_blocked", blocked(() -> {
            callAccount("setAuthToken",
                    new Class<?>[]{int.class, Account.class, String.class, String.class},
                    targetUser, targetAccount, TOKEN_TYPE, "rejected-cross-user-token");
            return null;
        }));
        result.putBoolean("cross_gms_pm_blocked", blockedOrNull(() ->
                callVirtualClient(PACKAGE_CLIENT, "getPackageInfo",
                        new Class<?>[]{String.class, int.class, int.class},
                        "com.google.android.gms", 0, targetUser)));
        result.putBoolean("cross_vending_pm_blocked", blockedOrNull(() ->
                callVirtualClient(PACKAGE_CLIENT, "getPackageInfo",
                        new Class<?>[]{String.class, int.class, int.class},
                        "com.android.vending", 0, targetUser)));
        result.putBoolean("cross_clear_target_blocked", blockedOrFalse(() ->
                callVirtualClient(CORE_CLIENT, "clearPackageAsUser",
                        new Class<?>[]{int.class, String.class},
                        targetUser, "com.google.android.gms")));
        result.putBoolean("cross_uninstall_target_blocked", blockedOrFalse(() ->
                callVirtualClient(CORE_CLIENT, "uninstallPackageAsUser",
                        new Class<?>[]{String.class, int.class},
                        "com.google.android.gms", targetUser)));
        result.putBoolean("cross_admin_mutation_blocked", blocked(() -> {
            callVirtualClient(CORE_CLIENT, "setPackageHidden",
                    new Class<?>[]{int.class, String.class, boolean.class},
                    targetUser, getPackageName(), true);
            return null;
        }));
        result.putBoolean("generic_trusted_bind_blocked", blockedOrFalse(() ->
                callVirtualClient(CORE_CLIENT, "installPackageAsUser",
                        new Class<?>[]{int.class, String.class},
                        selfUser, "com.google.android.gms")));

        ProviderInfo provider = (ProviderInfo) requiredVirtualClientCall(
                PACKAGE_CLIENT,
                "getProviderInfo",
                new Class<?>[]{ComponentName.class, int.class, int.class},
                new ComponentName(this, CrossUserFixtureProvider.class), 0, selfUser);
        result.putBoolean("same_account_list_allowed", succeedsNonEmpty(() ->
                callAccount("getAccounts", new Class<?>[]{int.class, String.class},
                        selfUser, requestedAccountType)));
        result.putBoolean("same_password_allowed", succeedsNonNull(() ->
                callAccount("getPassword", new Class<?>[]{int.class, Account.class},
                        selfUser, selfAccount)));
        result.putBoolean("same_token_allowed", succeedsNonNull(() ->
                callAccount("peekAuthToken",
                        new Class<?>[]{int.class, Account.class, String.class},
                        selfUser, selfAccount, TOKEN_TYPE)));
        result.putBoolean("same_password_write_allowed", succeeds(() -> {
            callAccount("setPassword", new Class<?>[]{int.class, Account.class, String.class},
                    selfUser, selfAccount, "updated-self-password");
            return null;
        }));
        result.putBoolean("same_token_write_allowed", succeeds(() -> {
            callAccount("setAuthToken",
                    new Class<?>[]{int.class, Account.class, String.class, String.class},
                    selfUser, selfAccount, TOKEN_TYPE, "updated-self-token");
            return null;
        }));
        result.putBoolean("same_pm_allowed", succeedsNonNull(() ->
                callVirtualClient(PACKAGE_CLIENT, "getPackageInfo",
                        new Class<?>[]{String.class, int.class, int.class},
                        getPackageName(), 0, selfUser)));
        result.putBoolean("same_provider_allowed", succeedsNonNull(() ->
                callVirtualClient(ACTIVITY_CLIENT, "acquireProviderClient",
                        new Class<?>[]{int.class, ProviderInfo.class}, selfUser, provider)));
        result.putBoolean("cross_provider_blocked", blockedOrNull(() ->
                callVirtualClient(ACTIVITY_CLIENT, "acquireProviderClient",
                        new Class<?>[]{int.class, ProviderInfo.class}, targetUser, provider)));
        result.putBoolean(COMPLETE, true);
        if (!result.commit()) {
            throw new IllegalStateException("Unable to persist cross-user probe result");
        }
        finish();
    }

    private static boolean blockedOrEmpty(ThrowingCall call) {
        try {
            Object value = call.run();
            return value instanceof Object[] && ((Object[]) value).length == 0;
        } catch (Throwable failure) {
            return isSecurityFailure(failure);
        }
    }

    private static boolean blockedOrNull(ThrowingCall call) {
        try {
            return call.run() == null;
        } catch (Throwable failure) {
            return isSecurityFailure(failure);
        }
    }

    private static boolean blockedOrFalse(ThrowingCall call) {
        try {
            Object value = call.run();
            return Boolean.FALSE.equals(value);
        } catch (Throwable failure) {
            return isSecurityFailure(failure);
        }
    }

    private static boolean blocked(ThrowingCall call) {
        try {
            call.run();
            return false;
        } catch (Throwable failure) {
            return isSecurityFailure(failure);
        }
    }

    private static boolean succeedsNonEmpty(ThrowingCall call) {
        try {
            Object value = call.run();
            return value instanceof Object[] && ((Object[]) value).length > 0;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean succeedsNonNull(ThrowingCall call) {
        try {
            return call.run() != null;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean succeeds(ThrowingCall call) {
        try {
            call.run();
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean isSecurityFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof SecurityException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static Object callVirtualClient(
            String className,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Class<?> clientType = loadRuntimeClass(className);
        Object client = clientType.getMethod("get").invoke(null);
        try {
            return clientType.getMethod(methodName, parameterTypes).invoke(client, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw wrapped;
        }
    }

    private static Object callAccount(
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Class<?> clientType = loadRuntimeClass(ACCOUNT_CLIENT);
        Object client = clientType.getMethod("get").invoke(null);
        Object remote = clientType.getMethod("getRemote").invoke(client);
        Class<?> accountInterface = Class.forName(
                ACCOUNT_INTERFACE, true, clientType.getClassLoader());
        try {
            return accountInterface.getMethod(methodName, parameterTypes).invoke(remote, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw wrapped;
        }
    }

    private static Class<?> loadRuntimeClass(String className) throws ClassNotFoundException {
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();
        addLoaderAndParents(loaders, sRuntimeLoader);
        addLoaderAndParents(loaders, Thread.currentThread().getContextClassLoader());
        addLoaderAndParents(loaders, ClassLoader.getSystemClassLoader());
        addLoaderAndParents(loaders, MaliciousCrossUserActivity.class.getClassLoader());
        ClassNotFoundException last = null;
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(className, true, loader);
            } catch (ClassNotFoundException missing) {
                last = missing;
            }
        }
        throw last != null ? last : new ClassNotFoundException(className);
    }

    private static void addLoaderAndParents(
            LinkedHashSet<ClassLoader> loaders,
            ClassLoader candidate
    ) {
        while (candidate != null && loaders.add(candidate)) {
            candidate = candidate.getParent();
        }
    }

    private static Object requiredVirtualClientCall(
            String className,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) {
        try {
            return callVirtualClient(className, methodName, parameterTypes, arguments);
        } catch (Exception failure) {
            throw new IllegalStateException("Required virtual-client control failed", failure);
        }
    }

    private interface ThrowingCall {
        Object run() throws Exception;
    }

    private static final String CORE_CLIENT = "com.lody.virtual.client.core.VirtualCore";
    private static final String PACKAGE_CLIENT = "com.lody.virtual.client.ipc.VPackageManager";
    private static final String ACTIVITY_CLIENT = "com.lody.virtual.client.ipc.VActivityManager";
    private static final String ACCOUNT_CLIENT = "com.lody.virtual.client.ipc.VAccountManager";
    private static final String ACCOUNT_INTERFACE = "com.lody.virtual.server.IAccountManager";
    private static volatile ClassLoader sRuntimeLoader;
}
