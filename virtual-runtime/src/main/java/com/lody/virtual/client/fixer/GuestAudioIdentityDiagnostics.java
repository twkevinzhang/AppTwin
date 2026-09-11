package com.lody.virtual.client.fixer;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.lody.virtual.helper.utils.Reflect;

/** Opt-in, process-bounded identity snapshots. Never constructs or starts a player. */
public final class GuestAudioIdentityDiagnostics {
    private static final String TAG = "GuestAudioIdentity";
    private static final int MAX_SNAPSHOTS = 48;
    private static int snapshots;
    private static int playerSnapshots;
    private static int providerSnapshots;

    private GuestAudioIdentityDiagnostics() { }

    public static void snapshot(String stage, Context context) {
        if (Build.VERSION.SDK_INT < 31 || !reserveSnapshot()) {
            return;
        }
        try {
            Object thread = Reflect.on("android.app.ActivityThread")
                    .call("currentActivityThread").get();
            Object application = thread == null ? null
                    : Reflect.on(thread).call("getApplication").get();
            Object global = Reflect.on("android.app.ActivityThread")
                    .call("currentAttributionSource").get();
            // These are the exact two framework sources selected by MediaPlayer(context/null).
            // No Binder tokens, attribution tags, Intent extras, URLs or media data are logged.
            Log.i(TAG, "stage=" + stage + " pid=" + Process.myPid()
                    + " processUid=" + Process.myUid()
                    + " context=" + describeContext(context)
                    + " application=" + describeContext(application)
                    + " global=" + describeSource(global));
            if (global == null) {
                Object pm = Reflect.on("android.app.AppGlobals")
                        .call("getPackageManager").get();
                String[] packages = Reflect.on(pm)
                        .call("getPackagesForUid", Process.myUid()).get();
                Log.i(TAG, "stage=" + stage + " fallbackSelfPackage="
                        + (packages == null || packages.length == 0 ? "none" : packages[0]));
            }
        } catch (Throwable error) {
            // Exception messages may contain guest data; only emit the exception type.
            Log.i(TAG, "stage=" + stage + " unavailable=" + error.getClass().getSimpleName());
        }
    }

    private static synchronized boolean reserveSnapshot() {
        if (snapshots >= MAX_SNAPSHOTS) {
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        snapshots++;
        return true;
    }

    private static boolean isEnabled() {
        try {
            String enabled = Reflect.on("android.os.SystemProperties")
                    .call("get", "debug.apptwin.audio_identity", "0").get();
            if (!"1".equals(enabled)) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    /** Logs at the existing audio-service boundary, preserving all arguments and results. */
    public static int beforeTrackPlayer(Object[] args) {
        int sample;
        synchronized (GuestAudioIdentityDiagnostics.class) {
            if (playerSnapshots >= 12 || !isEnabled()) {
                return 0;
            }
            sample = ++playerSnapshots;
        }
        try {
            Object global = Reflect.on("android.app.ActivityThread")
                    .call("currentAttributionSource").get();
            String op = Reflect.on("android.app.ActivityThread")
                    .call("currentOpPackageName").get();
            String type = "unavailable";
            if (args != null && args.length > 0 && args[0] != null
                    && "android.media.PlayerBase$PlayerIdCard".equals(
                            args[0].getClass().getName())) {
                try {
                    Object value = Reflect.on(args[0]).get("mPlayerType");
                    if (value instanceof Integer) {
                        type = value.toString();
                    }
                } catch (Throwable ignored) { }
            }
            StringBuilder frames = new StringBuilder();
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            int count = 0;
            for (StackTraceElement frame : stack) {
                String name = frame.getClassName();
                if (name.equals(Thread.class.getName())
                        || name.equals(GuestAudioIdentityDiagnostics.class.getName())) {
                    continue;
                }
                if (count++ >= 24) {
                    break;
                }
                if (frames.length() > 0) {
                    frames.append(" <- ");
                }
                frames.append(name).append('.').append(frame.getMethodName());
            }
            Log.i(TAG, "stage=track-player sample=" + sample + " pid=" + Process.myPid()
                    + " playerType=" + type + " opPackage=" + op
                    + " global=" + describeSource(global) + " caller=" + frames);
        } catch (Throwable error) {
            Log.i(TAG, "stage=track-player sample=" + sample
                    + " unavailable=" + error.getClass().getSimpleName());
        }
        return sample;
    }

    public static void afterTrackPlayer(int sample, Object result) {
        if (sample > 0 && result instanceof Integer) {
            try {
                Log.i(TAG, "stage=track-player-result sample=" + sample
                        + " pid=" + Process.myPid() + " playerId=" + result);
            } catch (Throwable ignored) { }
        }
    }

    public static void providerCallCopy(Object original, Object copy) {
        synchronized (GuestAudioIdentityDiagnostics.class) {
            if (providerSnapshots >= 16 || !isEnabled()) {
                return;
            }
            providerSnapshots++;
        }
        try {
            Object global = Reflect.on("android.app.ActivityThread")
                    .call("currentAttributionSource").get();
            Log.i(TAG, "stage=provider-call-copy pid=" + Process.myPid()
                    + " originalId=" + Integer.toHexString(System.identityHashCode(original))
                    + " callId=" + Integer.toHexString(System.identityHashCode(copy))
                    + " globalIsOriginal=" + (global == original)
                    + " original=" + describeSource(original)
                    + " call=" + describeSource(copy)
                    + " global=" + describeSource(global));
        } catch (Throwable ignored) { }
    }

    private static String describeContext(Object context) {
        if (context == null) {
            return "null";
        }
        try {
            String pkg = Reflect.on(context).call("getPackageName").get();
            String op = Reflect.on(context).call("getOpPackageName").get();
            Object source = Reflect.on(context).call("getAttributionSource").get();
            return "{class=" + context.getClass().getName()
                    + ",id=" + Integer.toHexString(System.identityHashCode(context))
                    + ",basePackage=" + pkg + ",opPackage=" + op
                    + ",attribution=" + describeSource(source) + "}";
        } catch (Throwable error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private static String describeSource(Object source) {
        if (source == null) {
            return "null";
        }
        try {
            Object uid = Reflect.on(source).call("getUid").get();
            String pkg = Reflect.on(source).call("getPackageName").get();
            return "{uid=" + uid + ",package=" + pkg + "}";
        } catch (Throwable error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }
}
