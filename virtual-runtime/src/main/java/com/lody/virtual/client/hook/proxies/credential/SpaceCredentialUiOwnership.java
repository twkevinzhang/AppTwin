package com.lody.virtual.client.hook.proxies.credential;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.utils.VLog;
import java.util.Objects;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks only explicitly marked microG sign-in UI for exact-token cancellation. */
public final class SpaceCredentialUiOwnership {
    private static final String PREFIX = "apptwin.spaceCredential.ui.";
    public static final String EXTRA_SESSION_ID = PREFIX + "session";
    public static final String EXTRA_USER_ID = PREFIX + "user";
    public static final String EXTRA_RECEIVER = PREFIX + "receiver";
    public static final String EXTRA_DEADLINE = PREFIX + "deadline";
    public static final String RESULT_SESSION_ID = "session";
    public static final String RESULT_USER_ID = "user";
    public static final String RESULT_TOKEN = "token";
    public static final String RESULT_TASK_ID = "task";
    public static final String RESULT_KIND = "kind";
    public static final String RESULT_PROCESS_TOKEN = "processToken";
    public static final String RESULT_PID = "pid";
    public static final String ROOT = "org.microg.gms.auth.signin.AuthSignInActivity";
    public static final String CHILD = "org.microg.gms.auth.login.LoginActivity";
    private static final String GMS = "com.google.android.gms";
    private static final String ACTION = "com.google.android.gms.auth.GOOGLE_SIGN_IN";
    private static final int MAX_SESSIONS = 16;
    private static final Map<Activity, OwnedUi> OWNED = new IdentityHashMap<>();

    private SpaceCredentialUiOwnership() { }

    public static void attach(Intent intent, int userId, String sessionId, ResultReceiver receiver) {
        attach(intent, userId, sessionId, receiver, SystemClock.uptimeMillis() + 120_000L);
    }

    public static void attach(Intent intent, int userId, String sessionId, ResultReceiver receiver,
            long deadline) {
        if (intent.getClipData() != null) {
            throw new IllegalArgumentException("Sign-in intent already has clip data");
        }
        // Keep this capability parcel separate from the provider-specific config parcel.
        // Reading one ordinary extra may deserialize all sibling config values using a
        // different SafeParcelable ABI before the guest has initialized its own loader.
        Intent capability = new Intent(PREFIX + "CAPABILITY");
        capability.putExtra(EXTRA_SESSION_ID, sessionId);
        capability.putExtra(EXTRA_USER_ID, userId);
        // Anonymous ResultReceiver subclasses must cross processes as the framework base
        // class, otherwise Bundle records the caller's unavailable implementation name.
        capability.putExtra(EXTRA_RECEIVER, frameworkReceiver(receiver));
        capability.putExtra(EXTRA_DEADLINE, deadline);
        intent.setClipData(new ClipData(PREFIX, new String[]{ClipDescription.MIMETYPE_TEXT_INTENT},
                new ClipData.Item(capability)));
    }

    public static void onCreate(Activity activity) {
        if (activity == null || !GMS.equals(VClientImpl.get().getCurrentPackage())
                || !GMS.equals(activity.getPackageName())) return;
        String className = activity.getClass().getName();
        int userId = VUserHandle.myUserId();
        Intent intent = activity.getIntent();
        IBinder token = mirror.android.app.Activity.mToken.get(activity);
        if (token == null || intent == null) return;
        int ownershipStage = 0;
        try {
            if (ROOT.equals(className)) {
                if (!ACTION.equals(intent.getAction())) return;
                ownershipStage = 1;
                Intent capability = capability(intent);
                if (capability == null) return;
                ownershipStage = 2;
                String sessionId = capability.getStringExtra(EXTRA_SESSION_ID);
                ownershipStage = 3;
                ResultReceiver receiver = capability.getParcelableExtra(EXTRA_RECEIVER);
                ownershipStage = 4;
                if (!validRoot(className, intent.getAction(), userId,
                        capability.getIntExtra(EXTRA_USER_ID, -1), sessionId, receiver != null)) return;
                ownershipStage = 5;
                ComponentName expectedCaller = VActivityManager.get().getCallingActivity(token);
                ownershipStage = 6;
                ComponentName actualCaller = activity.getCallingActivity();
                VLog.i("SpaceCredential", "checkpoint=owned-ui-caller callerKnown=%s callerMatchesVirtual=%s",
                        expectedCaller != null, Objects.equals(expectedCaller, actualCaller));
                ownershipStage = 7;
                long deadline = capability.getLongExtra(EXTRA_DEADLINE, -1L);
                if (deadline <= 0) return;
                ownershipStage = 8;
                IBinder processToken = VClientImpl.get().getToken();
                if (processToken == null || activity.getTaskId() < 0) return;
                ownershipStage = 9;
                OwnedUi owned = new OwnedUi(sessionId, userId, activity.getTaskId(),
                        Process.myPid(), processToken, receiver);
                synchronized (OWNED) {
                    if (OWNED.size() >= MAX_SESSIONS) return;
                    OWNED.put(activity, owned);
                }
                owned.timeout = () -> expire(owned);
                owned.handler.postDelayed(owned.timeout, remaining(deadline, SystemClock.uptimeMillis()));
                ownershipStage = 10;
                report(owned, token, "root");
                VLog.i("SpaceCredential", "checkpoint=owned-ui-registered kind=root");
            } else if (CHILD.equals(className)) {
                ownershipStage = 11;
                ComponentName caller = activity.getCallingActivity();
                if (caller == null || !GMS.equals(caller.getPackageName())
                        || !ROOT.equals(caller.getClassName())) return;
                OwnedUi match = null;
                synchronized (OWNED) {
                    for (Map.Entry<Activity, OwnedUi> entry : OWNED.entrySet()) {
                        OwnedUi candidate = entry.getValue();
                        if (!ROOT.equals(entry.getKey().getClass().getName())) continue;
                        if (candidate.userId == userId && candidate.taskId == activity.getTaskId()
                                && candidate.pid == Process.myPid()
                                && candidate.processToken.equals(VClientImpl.get().getToken())) {
                            if (match != null && match != candidate) return;
                            match = candidate;
                        }
                    }
                    if (match == null || OWNED.size() >= MAX_SESSIONS) return;
                    OWNED.put(activity, match);
                }
                ownershipStage = 12;
                report(match, token, "child");
                VLog.i("SpaceCredential", "checkpoint=owned-ui-registered kind=child");
            }
        } catch (RuntimeException error) {
            if (ROOT.equals(className) || CHILD.equals(className)) {
                VLog.i("SpaceCredential", "checkpoint=owned-ui-registration-failed stage=%s type=%s",
                        ownershipStage, error.getClass().getName());

            }
            // An unrelated or malformed guest intent never acquires ownership.
        }
    }

    private static ResultReceiver frameworkReceiver(ResultReceiver receiver) {
        if (receiver == null) throw new IllegalArgumentException("Missing result capability");
        Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    static Intent capability(Intent source) {
        ClipData clip = source.getClipData();
        if (clip == null || clip.getItemCount() != 1 || clip.getDescription() == null
                || clip.getDescription().getLabel() == null
                || !PREFIX.contentEquals(clip.getDescription().getLabel())
                || !clip.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT)) return null;
        ClipData.Item item = clip.getItemAt(0);
        if (item.getUri() != null || item.getText() != null || item.getHtmlText() != null) return null;
        Intent capability = item.getIntent();
        if (capability == null || !(PREFIX + "CAPABILITY").equals(capability.getAction())
                || capability.getData() != null || capability.getClipData() != null) return null;
        capability.setExtrasClassLoader(ResultReceiver.class.getClassLoader());
        return capability;
    }

    static boolean validRoot(String className, String action, int actualUser, int requestedUser,
            String sessionId, boolean hasReceiver) {
        if (!ROOT.equals(className) || !ACTION.equals(action) || actualUser != requestedUser
                || !hasReceiver || sessionId == null) return false;
        try { return UUID.fromString(sessionId).toString().equals(sessionId); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    public static void onDestroy(Activity activity) {
        synchronized (OWNED) {
            OwnedUi owned = OWNED.remove(activity);
            if (owned != null && ROOT.equals(activity.getClass().getName())) remove(owned);
        }
    }

    static long remaining(long deadline, long now) {
        if (deadline <= now) return 0L;
        return Math.min(120_000L, deadline - now);
    }

    private static void expire(OwnedUi owned) {
        ArrayList<Activity> children = new ArrayList<>();
        Activity root = null;
        synchronized (OWNED) {
            for (Map.Entry<Activity, OwnedUi> entry : OWNED.entrySet()) {
                if (entry.getValue() != owned) continue;
                if (ROOT.equals(entry.getKey().getClass().getName())) root = entry.getKey();
                else children.add(entry.getKey());
            }
            remove(owned);
        }
        // Only activities registered by this exact capability in this UI process are ended.
        // This local deadline still executes while the caller process is cached/frozen.
        for (int index = children.size() - 1; index >= 0; index--) children.get(index).finish();
        if (root != null) {
            root.setResult(Activity.RESULT_CANCELED);
            root.finish();
        }
        VLog.i("SpaceCredential", "checkpoint=owned-ui-deadline");
    }

    private static void remove(OwnedUi owned) {
        if (owned.timeout != null) owned.handler.removeCallbacks(owned.timeout);
        synchronized (OWNED) {
            for (Activity activity : new ArrayList<>(OWNED.keySet())) {
                if (OWNED.get(activity) == owned) OWNED.remove(activity);
            }
        }
    }

    private static void report(OwnedUi owned, IBinder token, String kind) {
        Bundle result = new Bundle();
        result.putString(RESULT_SESSION_ID, owned.sessionId);
        result.putInt(RESULT_USER_ID, owned.userId);
        result.putBinder(RESULT_TOKEN, token);
        result.putInt(RESULT_TASK_ID, owned.taskId);
        result.putString(RESULT_KIND, kind);
        result.putBinder(RESULT_PROCESS_TOKEN, owned.processToken);
        result.putInt(RESULT_PID, owned.pid);
        owned.receiver.send(0, result);
    }

    private static final class OwnedUi {
        final String sessionId;
        final int userId;
        final int taskId;
        final int pid;
        final IBinder processToken;
        final ResultReceiver receiver;
        final Handler handler = new Handler(Looper.getMainLooper());
        Runnable timeout;
        OwnedUi(String sessionId, int userId, int taskId, int pid, IBinder processToken,
                ResultReceiver receiver) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.taskId = taskId;
            this.pid = pid;
            this.processToken = processToken;
            this.receiver = receiver;
        }
    }
}
