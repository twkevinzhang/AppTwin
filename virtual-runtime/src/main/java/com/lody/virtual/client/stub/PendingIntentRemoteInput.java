package com.lody.virtual.client.stub;

import android.app.RemoteInput;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.lody.virtual.BuildConfig;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Transfers framework reply results only, after the caller validates wrapper ownership. */
public final class PendingIntentRemoteInput {
    private static final String DATA_PREFIX = "android.remoteinput.dataTypeResultsData";
    private PendingIntentRemoteInput() { }

    public static Intent copyResults(Intent wrapper, Intent original, String route) {
        try {
            Intent result = new Intent(original);
            if (!isFrameworkResults(wrapper.getClipData())) return result;
            Bundle text = RemoteInput.getResultsFromIntent(wrapper);
            Set<String> dataKeys = dataResultKeys(wrapper);
            // Intent's copy constructor shares ClipData. Framework addResults mutates its
            // embedded intent, so detach that envelope before writing a reply.
            ClipData oldClip = original.getClipData();
            boolean originalResults = isFrameworkResults(oldClip);
            if (text != null || !dataKeys.isEmpty()) {
                if (originalResults) {
                    Intent detached = new Intent(oldClip.getItemAt(0).getIntent());
                    Bundle extras = detached.getExtras();
                    if (extras != null) {
                        for (String name : extras.keySet()) {
                            if (RemoteInput.EXTRA_RESULTS_DATA.equals(name) || name.startsWith(DATA_PREFIX)) {
                                Bundle nested = extras.getBundle(name);
                                if (nested != null) detached.putExtra(name, new Bundle(nested));
                            }
                        }
                    }
                    result.setClipData(new ClipData(new ClipDescription(oldClip.getDescription()),
                            new ClipData.Item(detached)));
                } else {
                    // Ordinary ClipData is not reply data; do not let framework getters inspect it.
                    result.setClipData(null);
                }
            }
            boolean before = originalResults && RemoteInput.getResultsFromIntent(original) != null;
            if (text != null) {
                RemoteInput[] inputs = new RemoteInput[text.size()];
                int index = 0;
                for (String key : text.keySet()) {
                    inputs[index++] = new RemoteInput.Builder(key).build();
                }
                RemoteInput.addResultsToIntent(inputs, result, text);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                for (String key : dataKeys) {
                    Map<String, Uri> data = RemoteInput.getDataResultsFromIntent(wrapper, key);
                    if (data != null && !data.isEmpty()) {
                        RemoteInput.addDataResultToIntent(new RemoteInput.Builder(key).build(), result, data);
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 28 && (text != null || !dataKeys.isEmpty())) {
                RemoteInput.setResultsSource(result, RemoteInput.getResultsSource(wrapper));
            }
            if (BuildConfig.DEBUG && "jp.naver.line.android".equals(wrapper.getStringExtra("_VA_|_creator_"))) {
                // Booleans only: no result keys, text, URI, target or exception contents.
                Log.i("PendingReplyTrace", "route=" + route + " outerText=" + (text != null)
                        + " outerData=" + !dataKeys.isEmpty() + " innerBefore=" + before
                        + " innerAfter=" + (RemoteInput.getResultsFromIntent(result) != null));
            }
            return result;
        } catch (RuntimeException malformed) {
            // Never deliver a partial/malformed reply, and never log its contents.
            return null;
        }
    }

    private static boolean isFrameworkResults(ClipData clip) {
        return clip != null && clip.getItemCount() == 1
                && clip.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT)
                && clip.getDescription().getLabel() != null
                && RemoteInput.RESULTS_CLIP_LABEL.contentEquals(clip.getDescription().getLabel());
    }

    private static Set<String> dataResultKeys(Intent wrapper) {
        Set<String> keys = new HashSet<>();
        if (Build.VERSION.SDK_INT < 26) return keys;
        ClipData clip = wrapper.getClipData();
        if (!isFrameworkResults(clip)) return keys;
        Intent envelope = clip.getItemAt(0).getIntent();
        Bundle extras = envelope == null ? null : envelope.getExtras();
        if (extras == null) return keys;
        // The public getter requires a result key. Enumerate only the framework data schema,
        // then read/write each result with the official RemoteInput APIs.
        for (String name : extras.keySet()) {
            if (!name.startsWith(DATA_PREFIX) || name.length() == DATA_PREFIX.length()) continue;
            Bundle values = extras.getBundle(name);
            if (values != null) keys.addAll(values.keySet());
        }
        return keys;
    }
}
