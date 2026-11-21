package com.lody.virtual.helper.compat;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides when a guest launch hands virtual-provider content to an app outside the virtual
 * environment. Such URIs are unknown to the platform, so they must be copied and re-granted by
 * the host instead of being forwarded.
 */
public final class OutboundSharePolicy {
    public static final int MAX_URIS = 32;
    private static final String CHOOSER = "android.intent.action.CHOOSER";
    private static final Set<String> PAYLOAD_ACTIONS = new LinkedHashSet<>(java.util.Arrays.asList(
            "android.intent.action.VIEW",
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE"));

    private OutboundSharePolicy() { }

    public interface Request<T> {
        String action(T request);
        T target(T request);
        /** data, ClipData and EXTRA_STREAM URIs in that order. */
        List<String> uris(T request);
    }

    public static final class Decision<T> {
        public final T payload;
        public final List<String> virtualUris;
        public final boolean chooser;
        /** Carries virtual URIs but cannot be served; forwarding it would crash the guest. */
        public final boolean rejected;
        Decision(T payload, List<String> virtualUris, boolean chooser, boolean rejected) {
            this.payload = payload;
            this.virtualUris = virtualUris;
            this.chooser = chooser;
            this.rejected = rejected;
        }
    }

    /** @return null when the launch must keep its existing path. */
    public static <T> Decision<T> decide(T request, Request<T> adapter,
                                         Predicate<String> isVirtualUri, boolean hasVirtualTarget) {
        if (request == null) return null;
        boolean chooser = CHOOSER.equals(adapter.action(request));
        T payload = chooser ? adapter.target(request) : request;
        if (payload == null || !PAYLOAD_ACTIONS.contains(adapter.action(payload))) return null;
        if (!chooser && hasVirtualTarget) return null;
        Set<String> all = new LinkedHashSet<>(adapter.uris(payload));
        List<String> virtual = new ArrayList<>();
        for (String uri : all) if (uri != null && isVirtualUri.test(uri)) virtual.add(uri);
        if (virtual.isEmpty()) return null;
        return new Decision<>(payload, Collections.unmodifiableList(virtual), chooser, all.size() > MAX_URIS);
    }

    public static Decision<Intent> decide(Intent request, Predicate<String> isVirtualUri,
                                          boolean hasVirtualTarget) {
        return decide(request, INTENT_ADAPTER, isVirtualUri, hasVirtualTarget);
    }

    private static final Request<Intent> INTENT_ADAPTER = new Request<Intent>() {
        @Override public String action(Intent value) { return value.getAction(); }
        @Override public Intent target(Intent value) {
            Object target = value.getParcelableExtra(Intent.EXTRA_INTENT);
            return target instanceof Intent ? (Intent) target : null;
        }
        @Override public List<String> uris(Intent value) {
            List<String> out = new ArrayList<>();
            for (Uri uri : intentUris(value)) out.add(uri.toString());
            return out;
        }
    };

    private static List<Uri> intentUris(Intent value) {
        List<Uri> out = new ArrayList<>();
        if (value.getData() != null) out.add(value.getData());
        ClipData clip = value.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null) out.add(uri);
        }
        Object stream = value.getExtras() == null ? null : value.getExtras().get(Intent.EXTRA_STREAM);
        if (stream instanceof Uri) out.add((Uri) stream);
        else if (stream instanceof List) for (Object item : (List<?>) stream) {
            if (item instanceof Uri) out.add((Uri) item);
        }
        return out;
    }

    /**
     * Rebuilds the payload with platform types only: guest Parcelable extras cannot be
     * unparcelled by the host process.
     */
    public static Intent sanitize(Intent payload, Map<Uri, Uri> replacements) {
        Intent out = new Intent(payload.getAction());
        String type = payload.getType();
        Uri data = payload.getData();
        if (data != null) out.setDataAndType(replace(data, replacements), type);
        else if (type != null) out.setType(type);
        if (payload.getCategories() != null) for (String c : payload.getCategories()) out.addCategory(c);
        CharSequence text = payload.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) out.putExtra(Intent.EXTRA_TEXT, text.toString());
        String subject = payload.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subject != null) out.putExtra(Intent.EXTRA_SUBJECT, subject);
        CharSequence title = payload.getCharSequenceExtra(Intent.EXTRA_TITLE);
        if (title != null) out.putExtra(Intent.EXTRA_TITLE, title.toString());
        String[] mimes = payload.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (mimes != null) out.putExtra(Intent.EXTRA_MIME_TYPES, mimes);

        Object stream = payload.getExtras() == null ? null : payload.getExtras().get(Intent.EXTRA_STREAM);
        if (stream instanceof Uri) {
            out.putExtra(Intent.EXTRA_STREAM, replace((Uri) stream, replacements));
        } else if (stream instanceof List) {
            ArrayList<Parcelable> list = new ArrayList<>();
            for (Object item : (List<?>) stream) if (item instanceof Uri) list.add(replace((Uri) item, replacements));
            out.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
        }
        Set<Uri> granted = new LinkedHashSet<>();
        for (Uri uri : intentUris(payload)) granted.add(replace(uri, replacements));
        ClipData clip = null;
        for (Uri uri : granted) {
            if (clip == null) clip = ClipData.newRawUri("", uri);
            else clip.addItem(new ClipData.Item(uri));
        }
        if (clip != null) out.setClipData(clip);
        out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return out;
    }

    private static Uri replace(Uri uri, Map<Uri, Uri> replacements) {
        Uri mapped = replacements.get(uri);
        return mapped != null ? mapped : uri;
    }
}
