package com.lody.virtual.helper.compat;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import com.lody.virtual.BuildConfig;
import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.os.VUserHandle;

/** Debug-only bounded metadata. No URIs, extras, filenames or session identifiers. */
public final class FilePickerDiagnostics {
    private static int events;
    private static long firstEvent = -1;
    private FilePickerDiagnostics() { }
    private static synchronized boolean reserve(int user, String pkg) {
        if (!BuildConfig.DEBUG || user != 2 || !"jp.naver.line.android".equals(pkg)
                || events >= 64) return false;
        try {
            String enabled = Reflect.on("android.os.SystemProperties")
                    .call("get", "debug.apptwin.file_picker", "0").get();
            if (!"1".equals(enabled)) return false;
            long now = SystemClock.elapsedRealtime();
            if (firstEvent < 0) firstEvent = now;
            if (now - firstEvent >= 120000L) return false;
            events++;
            return true;
        } catch (Throwable ignored) { return false; }
    }
    private static boolean guest() {
        try { return reserve(VUserHandle.myUserId(), VClientImpl.get().getCurrentPackage()); }
        catch (Throwable ignored) { return false; }
    }
    private static String action(String value) {
        if (Intent.ACTION_GET_CONTENT.equals(value)) return "GET_CONTENT";
        if (Intent.ACTION_OPEN_DOCUMENT.equals(value)) return "OPEN_DOCUMENT";
        if (Intent.ACTION_CHOOSER.equals(value)) return "CHOOSER";
        if ("android.provider.action.PICK_IMAGES".equals(value)) return "PICK_IMAGES";
        return "OTHER";
    }
    private static String method(String value) {
        switch (value) {
            case "startActivity": case "startActivityAsUser": case "startActivityAndWait":
            case "startActivityAsCaller": case "startActivityWithConfig":
            case "query": case "getType": case "openFile": case "openAssetFile":
            case "openTypedAssetFile": case "call": return value;
            default: return "OTHER";
        }
    }
    public static void launch(String route, String method, Intent intent, boolean resultTo,
                              int requestCode, boolean hostComponent) {
        try {
            if (!guest()) return;
            String safeRoute = "ENTRY";
            if ("STUB".equals(route) || "TRUSTED".equals(route)
                    || "CHOOSER".equals(route) || "EXTERNAL".equals(route)) safeRoute = route;
            Log.i("FilePickerDiagnostics", "stage=launch route=" + safeRoute
                    + " method=" + method(method) + " action=" + action(intent.getAction())
                    + " resultTo=" + resultTo + " requestCode=" + requestCode
                    + " hostComponent=" + hostComponent);
        } catch (Throwable ignored) { }
    }
    public static void denied(String method, boolean hasUri) {
        try {
            if (guest()) Log.i("FilePickerDiagnostics", "stage=provider-denied method="
                    + method(method) + " hasUri=" + hasUri);
        } catch (Throwable ignored) { }
    }
    /** Call only with server-validated immutable session ownership. */
    public static void session(int user, String pkg, String stage, int count, boolean ok) {
        try {
            if (!"PREPARE".equals(stage) && !"TAKE".equals(stage)
                    && !"RESULT".equals(stage) && !"GRANT_CHECK".equals(stage)
                    && !"LEDGER".equals(stage)) return;
            if (reserve(user, pkg)) Log.i("FilePickerDiagnostics", "stage=" + stage
                    + " count=" + count + " ok=" + ok);
        } catch (Throwable ignored) { }
    }
}
