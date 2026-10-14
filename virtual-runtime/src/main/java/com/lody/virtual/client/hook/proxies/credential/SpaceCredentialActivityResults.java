package com.lody.virtual.client.hook.proxies.credential;

import android.content.Intent;
import android.os.IBinder;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Process-local ownership of activity results used by the Space credential bridge. */
public final class SpaceCredentialActivityResults {
    private static final String RESULT_NAMESPACE = "apptwin:space-credential:";
    private static final int MAX_ENTRIES = 256;
    private static final long TOMBSTONE_MILLIS = 120_000L;
    private static final Map<Integer, Entry> ENTRIES = new LinkedHashMap<>();
    private static int nextCode = 0x6000;

    private SpaceCredentialActivityResults() { }

    public interface Callback {
        void onResult(int resultCode, Intent data);
    }

    public static final class Registration {
        public final int requestCode;
        public final String resultWho;
        private final Entry entry;

        private Registration(int requestCode, Entry entry) {
            this.requestCode = requestCode;
            this.entry = entry;
            this.resultWho = entry.resultWho;
        }

        public void cancel() {
            synchronized (ENTRIES) {
                if (ENTRIES.get(requestCode) == entry) {
                    entry.callback = null;
                    entry.expiresAt = System.nanoTime() + TOMBSTONE_MILLIS * 1_000_000L;
                }
            }
        }
    }

    public static Registration register(IBinder token, Callback callback) {
        if (token == null || callback == null) throw new IllegalArgumentException("Missing result owner");
        synchronized (ENTRIES) {
            prune();
            // Never evict a live result owner or reuse a request code with a pending late result.
            if (ENTRIES.size() >= MAX_ENTRIES) throw new IllegalStateException("Too many result owners");
            int code;
            do {
                code = nextCode++;
                if (nextCode > 0x7fff) nextCode = 0x6000;
            } while (ENTRIES.containsKey(code));
            Entry entry = new Entry(token, callback);
            ENTRIES.put(code, entry);
            return new Registration(code, entry);
        }
    }

    public static boolean consumeActivityResult(IBinder token, Object resultInfo) {
        if (resultInfo == null) return false;
        final String resultWho;
        try {
            Object who = read(resultInfo, "mResultWho");
            if (!(who instanceof String) || !((String) who).startsWith(RESULT_NAMESPACE)) {
                return false;
            }
            resultWho = (String) who;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
        // This namespace is exclusively bridge-owned, even after cancellation, expiry,
        // process recreation, or a mismatched activity token. Never forward it to guest code.
        if (token == null) return true;
        final int requestCode;
        final int resultCode;
        final Intent data;
        try {
            requestCode = (Integer) read(resultInfo, "mRequestCode");
            resultCode = (Integer) read(resultInfo, "mResultCode");
            Object value = read(resultInfo, "mData");
            if (value != null && !(value instanceof Intent)) return true;
            data = (Intent) value;
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return true;
        }
        Callback callback;
        synchronized (ENTRIES) {
            prune();
            Entry entry = ENTRIES.get(requestCode);
            if (entry == null || !entry.token.equals(token)
                    || !entry.resultWho.equals(resultWho)) return true;
            callback = entry.callback;
            entry.callback = null;
            entry.expiresAt = System.nanoTime() + TOMBSTONE_MILLIS * 1_000_000L;
        }
        if (callback != null) {
            try {
                callback.onResult(resultCode, data);
            } catch (RuntimeException ignored) {
                // Ownership is already terminal. Never leak the result to the guest if
                // a completed bridge callback rejects a stale framework payload.
            }
        }
        return true;
    }

    private static Object read(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void prune() {
        long now = System.nanoTime();
        Iterator<Entry> iterator = ENTRIES.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.callback == null && now >= entry.expiresAt) iterator.remove();
        }
    }

    private static final class Entry {
        final IBinder token;
        final String resultWho = RESULT_NAMESPACE + UUID.randomUUID();
        Callback callback;
        long expiresAt;

        Entry(IBinder token, Callback callback) {
            this.token = token;
            this.callback = callback;
        }
    }
}
