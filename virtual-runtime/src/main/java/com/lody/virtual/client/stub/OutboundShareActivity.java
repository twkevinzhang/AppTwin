package com.lody.virtual.client.stub;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.compat.OutboundSharePolicy;
import com.lody.virtual.remote.OutboundShareRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Host-process bridge: copies guest files into {@link OutboundFileStore} off the main thread,
 * then hands host-owned, read-granted URIs to the system.
 */
public final class OutboundShareActivity extends Activity {
    public static final String SESSION = "apptwin.outbound.session";
    private static final long PROGRESS_DELAY_MILLIS = 500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ProgressBar progress;
    private volatile boolean destroyed;
    private final Runnable showProgress = () -> { if (progress != null) progress.setVisibility(View.VISIBLE); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { finish(); return; }
        final OutboundShareRequest request = VActivityManager.get().takeOutboundShare(getIntent().getStringExtra(SESSION));
        if (request == null || !request.isWellFormed()) {
            if (request != null) request.closeSources();
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        progress = new ProgressBar(this);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        main.postDelayed(showProgress, PROGRESS_DELAY_MILLIS);
        new Thread(() -> {
            Intent launch = prepare(request);
            main.post(() -> deliver(launch));
        }, "apptwin-outbound-copy").start();
    }

    private Intent prepare(OutboundShareRequest request) {
        OutboundFileStore store = OutboundFileProvider.store(this);
        try {
            store.sweep();
            Map<Uri, Uri> replacements = new HashMap<>();
            for (int i = 0; i < request.sources.length; i++) {
                if (destroyed) return null;
                File target = store.create(request.userId, request.names[i], request.mimes[i]);
                copy(request.sources[i], target);
                store.touch(target);
                replacements.put(Uri.parse(request.uris[i]), OutboundFileProvider.uriFor(this, target));
            }
            Intent payload = OutboundSharePolicy.sanitize(request.intent, replacements);
            if (!request.chooser) return payload;
            Intent chooser = Intent.createChooser(payload, payload.getStringExtra(Intent.EXTRA_TITLE));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return chooser;
        } catch (IOException | RuntimeException failure) {
            return null;
        } finally {
            request.closeSources();
        }
    }

    private static void copy(ParcelFileDescriptor source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source.getFileDescriptor());
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (IOException failure) {
            target.delete();
            throw failure;
        }
    }

    private void deliver(Intent launch) {
        main.removeCallbacks(showProgress);
        if (launch != null && !destroyed) {
            try {
                startActivity(launch);
            } catch (RuntimeException unavailable) {
                // No app can open it, or the system refused the grant; never crash the bridge.
            }
        }
        finish();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacks(showProgress);
        super.onDestroy();
    }
}
