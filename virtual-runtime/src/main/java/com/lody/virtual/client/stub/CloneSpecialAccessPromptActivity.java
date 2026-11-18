package com.lody.virtual.client.stub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;

import com.lody.virtual.helper.compat.ClonePermissionPromptPolicy;

/**
 * Host-process prompt for a clone's special-access request. Granting forwards the original system
 * settings intent so the user enables it for AppTwin there; cancelling reports RESULT_CANCELED.
 */
public final class CloneSpecialAccessPromptActivity extends Activity {
    public static final String EXTRA_REQUEST = "apptwin.specialAccess.request";
    private static final int REQUEST_SETTINGS = 1;
    private boolean finished;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        if (state != null) return;
        Intent request = getIntent().getParcelableExtra(EXTRA_REQUEST);
        String label = request == null ? null : ClonePermissionPromptPolicy.specialAccessLabel(request.getAction());
        if (label == null) { finish(); return; }
        new AlertDialog.Builder(this)
                .setMessage(label)
                .setCancelable(true)
                .setNegativeButton("取消", (d, w) -> finish())
                .setPositiveButton("提供", (d, w) -> openSettings(request))
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void openSettings(Intent request) {
        try {
            startActivityForResult(request, REQUEST_SETTINGS);
        } catch (RuntimeException unavailable) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_SETTINGS || finished) return;
        finished = true;
        setResult(result, data);
        finish();
    }
}
