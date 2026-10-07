package com.lody.virtual.client.stub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.lody.virtual.client.ipc.VActivityManager;

/** Runs in the host process; guest input only identifies an immutable server session. */
public final class TrustedUriPickerActivity extends Activity {
    public static final String SESSION = "apptwin.picker.session";
    private String session;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        session = getIntent().getStringExtra(SESSION);
        if (state != null) return;
        Intent request = VActivityManager.get().takeUriPickerRequest(session);
        if (request == null) { setResult(RESULT_CANCELED); finish(); return; }
        try { startActivityForResult(request, 1); }
        catch (RuntimeException unavailable) {
            VActivityManager.get().completeUriPicker(session, RESULT_CANCELED, null);
            setResult(RESULT_CANCELED); finish();
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1) return;
        if (result == RESULT_OK && data != null
                && (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            java.util.List<android.net.Uri> selected = new java.util.ArrayList<>();
            if (data.getData() != null) selected.add(data.getData());
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                android.net.Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
            for (android.net.Uri uri : selected) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException transientOnly) { /* GET_CONTENT need not offer persistence. */ }
            }
        }
        boolean accepted = VActivityManager.get().completeUriPicker(session, result, data);
        setResult(accepted ? result : RESULT_CANCELED, accepted ? data : null);
        finish();
    }
}
