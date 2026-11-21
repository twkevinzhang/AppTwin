package com.lody.virtual.client.hook.proxies.am;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.client.stub.OutboundShareActivity;
import com.lody.virtual.helper.compat.OutboundSharePolicy;
import com.lody.virtual.remote.OutboundShareRequest;

import java.util.HashMap;
import java.util.Map;

/** Guest-side half of the outbound file bridge: opens the guest's own URIs, never copies. */
final class OutboundShareLauncher {
    /**
     * Outbound launch that cannot be served. The caller must report START_SUCCESS without
     * launching: Instrumentation turns START_INTENT_NOT_RESOLVED into ActivityNotFoundException.
     */
    static final Intent REJECTED = new Intent();

    private OutboundShareLauncher() { }

    /** @return null to keep the existing launch path, {@link #REJECTED}, or the host bridge intent. */
    static Intent bridgeFor(Intent intent, int userId, IBinder resultTo, Context hostContext) {
        boolean chooser = Intent.ACTION_CHOOSER.equals(intent.getAction());
        boolean hasVirtualTarget = !chooser
                && VirtualCore.get().resolveActivityInfo(intent, userId) != null;
        final Map<String, Boolean> authorities = new HashMap<>();
        OutboundSharePolicy.Decision<Intent> decision = OutboundSharePolicy.decide(intent,
                uri -> isVirtualUri(uri, userId, authorities), hasVirtualTarget);
        if (decision == null) return null;
        if (decision.rejected || resultTo == null) return REJECTED;

        int n = decision.virtualUris.size();
        ParcelFileDescriptor[] sources = new ParcelFileDescriptor[n];
        String[] names = new String[n];
        String[] mimes = new String[n];
        String[] uris = decision.virtualUris.toArray(new String[0]);
        OutboundShareRequest request = null;
        try {
            ContentResolver resolver = VClientImpl.get().getCurrentApplication().getContentResolver();
            for (int i = 0; i < n; i++) {
                Uri uri = Uri.parse(uris[i]);
                sources[i] = resolver.openFileDescriptor(uri, "r");
                if (sources[i] == null) return REJECTED;
                names[i] = displayName(resolver, uri);
                mimes[i] = resolver.getType(uri);
            }
            Intent sanitized = OutboundSharePolicy.sanitize(decision.payload, new HashMap<>());
            if (chooser) {
                CharSequence title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);
                if (title != null) sanitized.putExtra(Intent.EXTRA_TITLE, title.toString());
            }
            request = new OutboundShareRequest(sanitized, sources, names, mimes, uris, chooser, userId);
            String session = VActivityManager.get().prepareOutboundShare(request, resultTo);
            Intent bridge = new Intent();
            bridge.setComponent(new ComponentName(hostContext, OutboundShareActivity.class));
            bridge.putExtra(OutboundShareActivity.SESSION, session);
            return bridge;
        } catch (Exception unavailable) {
            return REJECTED;
        } finally {
            // The server holds its own duplicated descriptors.
            for (ParcelFileDescriptor source : sources) {
                if (source == null) continue;
                try { source.close(); } catch (java.io.IOException ignored) { }
            }
        }
    }

    private static boolean isVirtualUri(String value, int userId, Map<String, Boolean> cache) {
        Uri uri = Uri.parse(value);
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || uri.getAuthority() == null) return false;
        Boolean known = cache.get(uri.getAuthority());
        if (known == null) {
            known = VPackageManager.get().resolveContentProvider(uri.getAuthority(), 0, userId) != null;
            cache.put(uri.getAuthority(), known);
        }
        return known;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException unavailable) {
            // Fall back to the last path segment.
        }
        return uri.getLastPathSegment();
    }
}
