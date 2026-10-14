package com.lody.virtual.client.hook.proxies.credential;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import com.lody.virtual.client.ipc.VAccountManager;
import com.lody.virtual.helper.compat.ActivityManagerCompat;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.StubProcessOwner;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bridges Google ID requests to the pinned, virtual microG instance; never the host provider. */
public final class SpaceGoogleCredentialBridge {
    static final String GOOGLE_TYPE = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL";
    static final String PREFIX = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_";
    private static final String GMS = "com.google.android.gms";
    private static final String NO_CREDENTIAL = "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL";
    private static final String CANCELED = "android.credentials.GetCredentialException.TYPE_USER_CANCELED";
    private static final long TIMEOUT_MS = 120_000;
    private static final java.util.concurrent.ConcurrentMap<IBinder, Session> ACTIVE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private SpaceGoogleCredentialBridge() { }

    static Object execute(Object[] args) {
        Object callback = findCallback(args);
        Session session = new Session(callback);
        Object cancellation = session.cancellationTransport();
        new Handler(Looper.getMainLooper()).post(() -> session.start(args));
        return cancellation;
    }

    static Object findCallback(Object[] args) {
        if (args != null) for (Object value : args) {
            if (value == null) continue;
            try {
                value.getClass().getMethod("onError", String.class, String.class);
                return value;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    static void reject(Object[] args) {
        error(findCallback(args), NO_CREDENTIAL);
    }

    static void rejectPrepared(Object[] args) {
        try {
            Class<?> type = Class.forName("android.credentials.PrepareGetCredentialResponseInternal");
            Object response = type.getConstructor(boolean.class, java.util.Set.class,
                    boolean.class, boolean.class, android.app.PendingIntent.class)
                    .newInstance(false, java.util.Collections.emptySet(), false, false, null);
            notifyResponse(args, type, response);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No physical fallback. Framework API drift is an unavailable credential path.
            reject(args);
        }
    }

    static boolean notifyResponse(Object[] args, Class<?> responseType, Object response) {
        if (args == null) return false;
        for (Object value : args) {
            if (value == null) continue;
            try {
                Method method = value.getClass().getMethod("onResponse", responseType);
                method.setAccessible(true);
                method.invoke(value, response);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Do not confuse prepare and get callback protocols.
            } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
        }
        return false;
    }

    private static void error(Object callback, String type) {
        if (callback == null) return;
        try {
            Method method = callback.getClass().getMethod("onError", String.class, String.class);
            method.setAccessible(true);
            method.invoke(callback, type, "Space credentials are unavailable for this request");
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    static boolean isBeforeDeadline(long nowUptimeMillis, long deadlineUptimeMillis) {
        return nowUptimeMillis >= 0 && deadlineUptimeMillis > 0 && nowUptimeMillis < deadlineUptimeMillis;
    }

    static boolean hasConfiguredSpaceAccounts(int count) { return count > 0; }

    static boolean isPinnedArchiveVersion(int version) { return version == 250932030; }

    static boolean isGoogleType(String type) { return GOOGLE_TYPE.equals(type); }

    static String requestPrefix(String subtype) {
        if (subtype == null || subtype.isEmpty()) return PREFIX;
        if ("com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL".equals(subtype)) {
            return "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_";
        }
        throw new IllegalArgumentException("Unsupported Google credential subtype");
    }

    static final class Session {
        private final Object callback;
        private final SpaceCredentialCompletion terminal = new SpaceCredentialCompletion();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable timeout = () -> fail(CANCELED);
        private final long deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT_MS;
        private SpaceCredentialActivityResults.Registration result;
        private Activity activity;
        private IBinder token;
        private IBinder callbackBinder;
        private IBinder.DeathRecipient death;
        private ClassLoader loader;
        private int user;
        private int serial;
        private long generation;
        private long packageGeneration;
        private long gmsGeneration;
        private String stage = "entry";
        private final String sessionId = UUID.randomUUID().toString();
        private final java.util.List<IBinder> uiTokens = new java.util.ArrayList<>();
        private int uiTask = -1;
        private int uiPid = -1;
        private IBinder uiProcess;
        private boolean requiresSystemProvider;
        private final java.util.Set<String> providerNames = new java.util.HashSet<>();
        private String caller;
        private StubProcessOwner.Identity processOwner;

        Session(Object callback) { this.callback = callback; }

        Object cancellationTransport() {
            try {
                Binder binder = new Binder() {
                    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                            throws RemoteException {
                        if (code == INTERFACE_TRANSACTION) {
                            if (reply != null) reply.writeString("android.os.ICancellationSignal");
                            return true;
                        }
                        if (code == FIRST_CALL_TRANSACTION) {
                            data.enforceInterface("android.os.ICancellationSignal");
                            handler.post(() -> fail(CANCELED));
                            return true;
                        }
                        return super.onTransact(code, data, reply, flags);
                    }
                };
                return Class.forName("android.os.ICancellationSignal$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, binder);
            } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
        }

        void start(Object[] args) {
            if (terminal.isFinished()) return;
            try {
                if (callback == null || args == null || args.length == 0) throw new IllegalStateException();
                stage = "parse";
                Object request = args[0];
                stage = "option-count";
                Object values = request.getClass().getMethod("getCredentialOptions").invoke(request);
                com.lody.virtual.helper.utils.VLog.i("SpaceCredential", "optionCount=%d",
                        values instanceof List ? ((List<?>) values).size() : -1);
                if (!(values instanceof List) || ((List<?>) values).size() != 1) throw new IllegalStateException();
                Object option = ((List<?>) values).get(0);
                String type = (String) option.getClass().getMethod("getType").invoke(option);
                stage = "system-provider-required";
                requiresSystemProvider = Boolean.TRUE.equals(
                        option.getClass().getMethod("isSystemProviderRequired").invoke(option));
                stage = "allowed-provider-restriction";
                Object providers = option.getClass().getMethod("getAllowedProviders").invoke(option);
                com.lody.virtual.helper.utils.VLog.i("SpaceCredential", "allowedProviderCount=%d",
                        providers instanceof java.util.Set ? ((java.util.Set<?>) providers).size() : -1);
                if (!(providers instanceof java.util.Set)) throw new IllegalStateException();
                providerNames.clear();
                for (Object provider : (java.util.Set<?>) providers) {
                    if (!(provider instanceof android.content.ComponentName)) throw new IllegalStateException();
                    providerNames.add(((android.content.ComponentName) provider).flattenToString());
                }
                stage = "credential-type";
                if (!isGoogleType(type)) throw new IllegalStateException();
                Bundle data = (Bundle) option.getClass().getMethod("getCredentialRetrievalData").invoke(option);
                stage = "google-subtype";
                String requestPrefix = requestPrefix(data.getString(PREFIX + "GOOGLE_ID_TOKEN_SUBTYPE"));
                boolean siwg = !PREFIX.equals(requestPrefix);
                String clientId = data.getString(requestPrefix + "SERVER_CLIENT_ID");
                String nonce = data.getString(requestPrefix + "NONCE");
                // These require additional consent/authorization protocols; never silently downgrade.
                stage = "linked-service";
                if (data.getString(PREFIX + "LINKED_SERVICE_ID") != null) throw new IllegalStateException();
                stage = "verified-phone";
                if (data.getBoolean(PREFIX + "REQUEST_VERIFIED_PHONE_NUMBER", false)) throw new IllegalStateException();
                stage = "authorized-account-filter";
                if (!siwg && data.getBoolean(PREFIX + "FILTER_BY_AUTHORIZED_ACCOUNTS", false)) throw new IllegalStateException();
                stage = "client-id-absent";
                if (clientId == null || clientId.isEmpty()) throw new IllegalStateException();
                // Origin is an assertion made by privileged browsers, unsupported by this guest bridge.
                stage = "privileged-origin";
                if (request.getClass().getMethod("getOrigin").invoke(request) != null) throw new IllegalStateException();
                stage = "scope";
                caller = VClientImpl.get().getCurrentPackage();
                user = VUserHandle.myUserId();
                VUserInfo owner = VUserManager.get().getUserInfo(user);
                if (owner == null || !VirtualCore.get().isAppInstalledAsUser(user, GMS)) throw new IllegalStateException();
                InstalledAppInfo installed = VirtualCore.get().getInstalledAppInfo(GMS, 0);
                if (installed == null || installed.dependSystem || installed.apkPath == null
                        || installed.getApplicationInfo(user) == null
                        || !installed.apkPath.equals(installed.getApplicationInfo(user).sourceDir)
                        ) throw new IllegalStateException();
                android.content.pm.PackageInfo archive = VirtualCore.get().getContext().getPackageManager()
                        .getPackageArchiveInfo(installed.apkPath, 0);
                if (archive == null || !GMS.equals(archive.packageName)
                        || !isPinnedArchiveVersion(archive.versionCode)) throw new IllegalStateException();
                stage = "space-google-provider";
                if (!hasSpaceGoogleProvider(user, requiresSystemProvider, providerNames)) throw new IllegalStateException();
                stage = "space-account-empty";
                android.accounts.Account[] configured = VAccountManager.get().getAccounts(user, "com.google");
                if (!hasConfiguredSpaceAccounts(configured == null ? 0 : configured.length)) {
                    throw new IllegalStateException();
                }
                processOwner = VClientImpl.get().getProcessOwner();
                if (processOwner == null || !caller.equals(processOwner.getPackageName())
                        || processOwner.getVuid() != VClientImpl.get().getVUid()) throw new IllegalStateException();
                serial = owner.serialNumber;
                generation = owner.pendingIntentGeneration;
                packageGeneration = VUserManager.get().getPackagePendingIntentGeneration(caller, user);
                gmsGeneration = VUserManager.get().getPackagePendingIntentGeneration(GMS, user);
                activity = focusedActivity(caller);
                if (activity == null) throw new IllegalStateException();
                token = mirror.android.app.Activity.mToken.get(activity);
                if (token == null || ACTIVE.putIfAbsent(token, this) != null) throw new IllegalStateException();
                stage = "loader";
                loader = createPinnedLoader(installed.apkPath);
                stage = "intent";
                Intent intent = createSignInIntent(loader, caller, clientId, nonce,
                        data.getString("com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_HOSTED_DOMAIN_FILTER"));
                SpaceCredentialUiOwnership.attach(intent, user, sessionId, new ResultReceiver(handler) {
                    @Override protected void onReceiveResult(int code, Bundle data) {
                        acceptUiOwner(data);
                    }
                }, deadline);
                stage = "resolve";
                ActivityInfo info = VirtualCore.get().resolveActivityInfo(intent, user);
                if (info == null || !GMS.equals(info.packageName)) throw new IllegalStateException();
                result = SpaceCredentialActivityResults.register(token, this::complete);
                if (callback instanceof IInterface) {
                    callbackBinder = ((IInterface) callback).asBinder();
                    death = () -> handler.post(() -> fail(CANCELED));
                    callbackBinder.linkToDeath(death, 0);
                }
                handler.postAtTime(timeout, deadline);
                if (!current()) throw new IllegalStateException();
                stage = "launch";
                int status = VActivityManager.get().startActivity(intent, info, token, null,
                        result.resultWho, result.requestCode, user);
                if (status < 0) throw new IllegalStateException();
                com.lody.virtual.helper.utils.VLog.i("SpaceCredential", "checkpoint=launched");
            } catch (ReflectiveOperationException | RuntimeException | RemoteException | LinkageError ignored) {
                com.lody.virtual.helper.utils.VLog.w("SpaceCredential", "checkpoint=%s error=%s", stage, ignored.getClass().getSimpleName());
                fail(NO_CREDENTIAL);
            }
        }

        private boolean current() {
            if (!isBeforeDeadline(android.os.SystemClock.uptimeMillis(), deadline)) return false;
            VUserInfo owner = VUserManager.get().getUserInfo(user);
            StubProcessOwner.Identity currentProcess = VClientImpl.get().getProcessOwner();
            return owner != null && processOwner != null && currentProcess != null
                    && currentProcess.getGeneration() == processOwner.getGeneration()
                    && currentProcess.getServerToken() == processOwner.getServerToken()
                    && currentProcess.getServerToken() instanceof IBinder
                    && ((IBinder) currentProcess.getServerToken()).isBinderAlive()
                    && currentProcess.getVuid() == processOwner.getVuid()
                    && owner.serialNumber == serial
                    && owner.pendingIntentGeneration == generation
                    && gmsGeneration == VUserManager.get().getPackagePendingIntentGeneration(GMS, user)
                    && packageGeneration == VUserManager.get().getPackagePendingIntentGeneration(caller, user)
                    && caller.equals(VClientImpl.get().getCurrentPackage())
                    && VirtualCore.get().isAppInstalledAsUser(user, caller)
                    && VirtualCore.get().isAppInstalledAsUser(user, GMS)
                    && hasSpaceGoogleProvider(user, requiresSystemProvider, providerNames)
                    && activity != null && !activity.isFinishing() && !activity.isDestroyed();
        }

        void complete(int resultCode, Intent intent) {
            if (terminal.isFinished()) return;
            com.lody.virtual.helper.utils.VLog.i("SpaceCredential", "checkpoint=result");
            try {
                if (!current() || resultCode != Activity.RESULT_OK || intent == null) {
                    fail(CANCELED); return;
                }
                intent.setExtrasClassLoader(loader);
                Class<?> credentialType = loader.loadClass("com.google.android.gms.auth.api.signin.GoogleSignInAccount");
                Object credential = intent.getParcelableExtra("googleSignInAccount");
                if (credential == null || !credentialType.isInstance(credential)) throw new IllegalStateException();
                String id = (String) credentialType.getMethod("getEmail").invoke(credential);
                String idToken = (String) credentialType.getMethod("getIdToken").invoke(credential);
                if (id == null || id.isEmpty() || idToken == null || idToken.isEmpty()) throw new IllegalStateException();
                boolean member = false;
                android.accounts.Account[] accounts = VAccountManager.get().getAccounts(user, "com.google");
                if (accounts != null) for (android.accounts.Account account : accounts) {
                    if (account != null && id.equals(account.name)) { member = true; break; }
                }
                if (!member) throw new IllegalStateException();
                Bundle data = new Bundle();
                data.putString(PREFIX + "ID", id);
                data.putString(PREFIX + "ID_TOKEN", idToken);
                copyString(credential, credentialType, "getDisplayName", data, "DISPLAY_NAME");
                copyString(credential, credentialType, "getGivenName", data, "GIVEN_NAME");
                copyString(credential, credentialType, "getFamilyName", data, "FAMILY_NAME");
                Object picture = credentialType.getMethod("getPhotoUrl").invoke(credential);
                if (picture != null) data.putString(PREFIX + "PROFILE_PICTURE_URI", picture.toString());
                Class<?> frameworkCredential = Class.forName("android.credentials.Credential");
                Object value = frameworkCredential.getConstructor(String.class, Bundle.class).newInstance(GOOGLE_TYPE, data);
                Class<?> responseType = Class.forName("android.credentials.GetCredentialResponse");
                Object response = responseType.getConstructor(frameworkCredential).newInstance(value);
                if (!current()) { fail(CANCELED); return; }
                Method onResponse = callback.getClass().getMethod("onResponse", responseType);
                onResponse.setAccessible(true);
                terminal.finish(() -> {
                    cleanup();
                    if (!isBeforeDeadline(android.os.SystemClock.uptimeMillis(), deadline)) {
                        error(callback, CANCELED);
                        return;
                    }
                    try {
                        onResponse.invoke(callback, response);
                        com.lody.virtual.helper.utils.VLog.i("SpaceCredential", "checkpoint=success");
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        com.lody.virtual.helper.utils.VLog.w("SpaceCredential", "checkpoint=callback-unavailable");
                    }
                });
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                com.lody.virtual.helper.utils.VLog.w("SpaceCredential", "checkpoint=result-error type=%s", ignored.getClass().getSimpleName());
                fail(NO_CREDENTIAL);
            }
        }

        void fail(String type) {
            terminal.finish(() -> {
                cleanup();
                com.lody.virtual.helper.utils.VLog.i("SpaceCredential", "checkpoint=terminal-error stage=%s", stage);
                error(callback, type);
            });
        }

        private void acceptUiOwner(Bundle data) {
            if (data == null || !sessionId.equals(data.getString(SpaceCredentialUiOwnership.RESULT_SESSION_ID))
                    || user != data.getInt(SpaceCredentialUiOwnership.RESULT_USER_ID, -1)) return;
            IBinder ownedToken = data.getBinder(SpaceCredentialUiOwnership.RESULT_TOKEN);
            IBinder process = data.getBinder(SpaceCredentialUiOwnership.RESULT_PROCESS_TOKEN);
            int task = data.getInt(SpaceCredentialUiOwnership.RESULT_TASK_ID, -1);
            int pid = data.getInt(SpaceCredentialUiOwnership.RESULT_PID, -1);
            String kind = data.getString(SpaceCredentialUiOwnership.RESULT_KIND);
            if (ownedToken == null || process == null || !process.isBinderAlive() || task < 0 || pid <= 0) return;
            if ("root".equals(kind)) {
                if (uiProcess != null && (!uiProcess.equals(process) || uiTask != task || uiPid != pid)) return;
                uiTask = task;
                uiPid = pid;
                uiProcess = process;
            } else if (!"child".equals(kind) || uiProcess == null
                    || !uiProcess.equals(process) || uiTask != task || uiPid != pid) return;
            if (terminal.isFinished()) {
                finishOwnedUi(ownedToken);
                return;
            }
            if (uiTokens.size() < 3 && !uiTokens.contains(ownedToken)) uiTokens.add(ownedToken);
        }

        private static void finishOwnedUi(IBinder token) {
            try {
                final boolean finished;
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    Class<?> clientType = Class.forName("android.app.ActivityClient");
                    Object client = clientType.getMethod("getInstance").invoke(null);
                    // DONT_FINISH_TASK_WITH_ACTIVITY=0 finishes only this exact activity token.
                    finished = Boolean.TRUE.equals(clientType.getMethod("finishActivity",
                            IBinder.class, int.class, Intent.class, int.class)
                            .invoke(client, token, Activity.RESULT_CANCELED, null, 0));
                } else {
                    finished = ActivityManagerCompat.finishActivity(token, Activity.RESULT_CANCELED, null);
                }
                com.lody.virtual.helper.utils.VLog.i("SpaceCredential", "checkpoint=owned-ui-finish accepted=%s", finished);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                com.lody.virtual.helper.utils.VLog.w("SpaceCredential", "checkpoint=owned-ui-finish-error type=%s",
                        ignored.getClass().getSimpleName());
            }
        }

        private void cleanup() {
            handler.removeCallbacks(timeout);
            if (token != null) ACTIVE.remove(token, this);
            for (int i = uiTokens.size() - 1; i >= 0; i--) {
                finishOwnedUi(uiTokens.get(i));
            }
            uiTokens.clear();
            if (result != null) result.cancel();
            if (callbackBinder != null && death != null) {
                try { callbackBinder.unlinkToDeath(death, 0); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static void copyString(Object source, Class<?> type, String method, Bundle data, String key)
            throws ReflectiveOperationException {
        Object value = type.getMethod(method).invoke(source);
        if (value instanceof String) data.putString(PREFIX + key, (String) value);
    }

    private static boolean hasSpaceGoogleProvider(int user, boolean systemRequired, java.util.Set<String> allowed) {
        android.content.ComponentName component = new android.content.ComponentName(
                SpaceGoogleProviderPolicy.PACKAGE, SpaceGoogleProviderPolicy.SERVICE);
        android.content.pm.ServiceInfo info = com.lody.virtual.client.ipc.VPackageManager.get()
                .getServiceInfo(component, 0, user);
        boolean declared = false;
        java.util.List<android.content.pm.ResolveInfo> resolved = com.lody.virtual.client.ipc.VPackageManager.get()
                .queryIntentServices(new Intent(SpaceGoogleProviderPolicy.ACTION)
                        .setPackage(SpaceGoogleProviderPolicy.PACKAGE), null, 0, user);
        if (resolved != null) for (android.content.pm.ResolveInfo candidate : resolved) {
            if (candidate.serviceInfo != null && SpaceGoogleProviderPolicy.PACKAGE.equals(candidate.serviceInfo.packageName)
                    && SpaceGoogleProviderPolicy.SERVICE.equals(candidate.serviceInfo.name)) declared = true;
        }
        return SpaceGoogleProviderPolicy.permits(true, true, systemRequired, declared,
                info != null && info.exported && info.enabled
                        && info.applicationInfo != null && info.applicationInfo.enabled,
                info == null ? null : info.permission, allowed);
    }

    static ClassLoader createPinnedLoader(String apkPath) {
        if (apkPath == null || apkPath.isEmpty()) throw new IllegalArgumentException("Missing pinned APK");
        // Android's boot loader owns framework classes; the app parent contains host GMS/Firebase
        // classes whose SafeParcelable ABI differs from the pinned microG implementation.
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        if (boot == null) throw new IllegalStateException("Framework class loader unavailable");
        return new dalvik.system.PathClassLoader(apkPath, boot);
    }

    static Intent createSignInIntent(ClassLoader loader, String caller, String clientId, String nonce, String hostedDomain)
            throws ReflectiveOperationException {
        Class<?> optionsType = loader.loadClass("com.google.android.gms.auth.api.signin.GoogleSignInOptions");
        Object builder = loader.loadClass(optionsType.getName() + "$Builder")
                .getConstructor(optionsType).newInstance(optionsType.getField("DEFAULT_SIGN_IN").get(null));
        builder.getClass().getMethod("requestEmail").invoke(builder);
        builder.getClass().getMethod("requestIdToken", String.class).invoke(builder, clientId);
        if (hostedDomain != null) builder.getClass().getMethod("setHostedDomain", String.class).invoke(builder, hostedDomain);
        Object options = builder.getClass().getMethod("build").invoke(builder);
        Class<?> configurationType = loader.loadClass(
                "com.google.android.gms.auth.api.signin.internal.SignInConfiguration");
        Object configuration = configurationType.getConstructor(String.class, optionsType)
                .newInstance(caller, options);
        return new Intent("com.google.android.gms.auth.GOOGLE_SIGN_IN")
                .setClassName(GMS, "org.microg.gms.auth.signin.AuthSignInActivity")
                .putExtra("config", (Parcelable) configuration)
                .putExtra("nonce", nonce);
    }

    private static Activity focusedActivity(String caller) throws ReflectiveOperationException {
        Object thread = VirtualCore.mainThread();
        Field activities = thread.getClass().getDeclaredField("mActivities");
        activities.setAccessible(true);
        Object records = activities.get(thread);
        Activity selected = null;
        if (records instanceof Map) for (Object record : ((Map<?, ?>) records).values()) {
            Field field = record.getClass().getDeclaredField("activity");
            field.setAccessible(true);
            Object value = field.get(record);
            if (!(value instanceof Activity)) continue;
            Activity candidate = (Activity) value;
            if (caller.equals(candidate.getPackageName()) && candidate.hasWindowFocus()
                    && !candidate.isFinishing() && !candidate.isDestroyed()) {
                if (selected != null) return null;
                selected = candidate;
            }
        }
        return selected;
    }
}
