package com.lody.virtual.client.stub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.helper.compat.ClonePermissionPromptPolicy;

import java.util.List;

/**
 * Host-process prompt for a clone's permission request. Cancelling reports denial without
 * remembering the choice; granting records the space grant, sending the user to system
 * settings first when the host app itself is not authorized by Android.
 */
public final class ClonePermissionPromptActivity extends Activity {
    public static final String EXTRA_PACKAGE = "apptwin.permissionPrompt.package";
    public static final String EXTRA_USER = "apptwin.permissionPrompt.user";

    private static final int REQUEST_SETTINGS = 1;
    private static final String STATE_STAGE = "stage";
    private static final int STAGE_ASK = 0;
    private static final int STAGE_SETTINGS = 1;
    private static final int STAGE_RESTART_HINT = 2;

    private String packageName;
    private int userId;
    private String[] requested;
    private List<String> controlled;
    private int stage = STAGE_ASK;
    private boolean finished;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        packageName = intent.getStringExtra(EXTRA_PACKAGE);
        userId = intent.getIntExtra(EXTRA_USER, -1);
        requested = intent.getStringArrayExtra(ClonePermissionPromptPolicy.EXTRA_NAMES);
        controlled = ClonePermissionPromptPolicy.controlled(requested);
        if (packageName == null || userId < 0 || requested == null || controlled.isEmpty()) {
            deliver();
            return;
        }
        if (state != null) stage = state.getInt(STATE_STAGE, STAGE_ASK);
        // Returning from settings is resolved in onActivityResult; a recreated settings stage
        // without a result can only be answered by asking again.
        if (stage == STAGE_SETTINGS) stage = STAGE_ASK;
        showStage();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_STAGE, stage);
    }

    private void showStage() {
        if (stage == STAGE_RESTART_HINT) showRestartHint(); else showAsk();
    }

    private void showAsk() {
        String message = join(ClonePermissionPromptPolicy.labels(controlled));
        new AlertDialog.Builder(this)
                .setTitle(appLabel())
                .setMessage(message)
                .setCancelable(true)
                .setNegativeButton("取消", (d, w) -> deliver())
                .setPositiveButton("提供", (d, w) -> onProvide())
                .setOnCancelListener(d -> deliver())
                .show();
    }

    private void onProvide() {
        if (ClonePermissionPromptPolicy.allHostSatisfied(controlled, hostGrants())) {
            applyGrants();
            return;
        }
        stage = STAGE_SETTINGS;
        try {
            startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)), REQUEST_SETTINGS);
        } catch (RuntimeException unavailable) {
            deliver();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_SETTINGS || finished) return;
        if (ClonePermissionPromptPolicy.allHostSatisfied(controlled, hostGrants())) {
            applyGrants();
        } else {
            deliver();
        }
    }

    private void applyGrants() {
        for (String permission : controlled) {
            VPackageManager.get().setRuntimePermissionGranted(permission, packageName, userId, true);
        }
        if (ClonePermissionPromptPolicy.mayNeedRestart(controlled)) {
            stage = STAGE_RESTART_HINT;
            showRestartHint();
        } else {
            deliver();
        }
    }

    private void showRestartHint() {
        new AlertDialog.Builder(this)
                .setTitle(appLabel())
                .setMessage("可能需要重啟 " + appLabel())
                .setCancelable(false)
                .setPositiveButton("知道了", (d, w) -> deliver())
                .show();
    }

    /** Reports the real current state of every requested permission, then closes. */
    private void deliver() {
        if (finished) return;
        finished = true;
        int[] results = new int[requested == null ? 0 : requested.length];
        for (int i = 0; i < results.length; i++) {
            boolean granted = packageName != null && userId >= 0 && requested[i] != null
                    && VPackageManager.get().checkPermission(requested[i], packageName, userId)
                    == PackageManager.PERMISSION_GRANTED;
            results[i] = granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
        setResult(RESULT_OK, new Intent()
                .putExtra(ClonePermissionPromptPolicy.EXTRA_NAMES, requested == null ? new String[0] : requested)
                .putExtra(ClonePermissionPromptPolicy.EXTRA_RESULTS, results));
        finish();
    }

    private ClonePermissionPromptPolicy.HostGrants hostGrants() {
        return new ClonePermissionPromptPolicy.HostGrants() {
            @Override public boolean granted(String permission) {
                return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            }
            @Override public boolean allFiles() {
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
            }
        };
    }

    private String appLabel() {
        try {
            ApplicationInfo info = VPackageManager.get().getApplicationInfo(packageName, 0, userId);
            if (info != null) {
                if (info.nonLocalizedLabel != null) return info.nonLocalizedLabel.toString();
                if (info.labelRes != 0) {
                    CharSequence label = getPackageManager().getResourcesForApplication(info)
                            .getText(info.labelRes);
                    if (label != null) return label.toString();
                }
            }
        } catch (Exception | LinkageError unavailable) { /* fall back to the package name */ }
        return String.valueOf(packageName);
    }

    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) out.append("、");
            out.append(item);
        }
        return out.toString();
    }
}
