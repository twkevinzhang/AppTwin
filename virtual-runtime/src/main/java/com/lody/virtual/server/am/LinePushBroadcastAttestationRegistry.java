package com.lody.virtual.server.am;

import android.os.SystemClock;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-time, short-lived attestations for exact microG-to-LINE C2DM wrappers. */
final class LinePushBroadcastAttestationRegistry {
    static final int MAX_PENDING = 64;
    static final long TTL_MILLIS = 5_000L;
    private static final int TOKEN_BYTES = 16;
    private static final int MAX_COLLISION_ATTEMPTS = 4;

    interface Clock {
        long now();
    }

    interface TokenSource {
        String next();
    }

    private final Clock clock;
    private final TokenSource tokenSource;
    private final Map<String, Entry> pending = new LinkedHashMap<>();

    LinePushBroadcastAttestationRegistry() {
        SecureRandom random = new SecureRandom();
        clock = SystemClock::elapsedRealtime;
        tokenSource = () -> randomToken(random);
    }

    LinePushBroadcastAttestationRegistry(Clock clock, TokenSource tokenSource) {
        this.clock = clock;
        this.tokenSource = tokenSource;
    }

    static boolean canIssue(String callerPackage, int callerVuid, int callerUserId,
            boolean callerReady, boolean currentOwner, boolean endpointActive,
            String action, String targetPackage, boolean implicit) {
        return TrustedGmsCloudMessagingSupervisor.GMS_PACKAGE.equals(callerPackage)
                && callerUserId > 0
                && callerVuid >= 0
                && callerUserId == com.lody.virtual.os.VUserHandle.getUserId(callerVuid)
                && callerReady
                && currentOwner
                && endpointActive
                && LinePushBroadcastPolicy.C2DM_RECEIVE.equals(action)
                && LinePushBroadcastPolicy.LINE_PACKAGE.equals(targetPackage)
                && implicit;
    }

    synchronized String issue(Binding binding) {
        if (binding == null) return null;
        cleanupExpiredLocked(clock.now());
        if (pending.size() >= MAX_PENDING) return null;
        for (int attempt = 0; attempt < MAX_COLLISION_ATTEMPTS; attempt++) {
            String token = tokenSource.next();
            if (token == null || token.isEmpty() || pending.containsKey(token)) continue;
            pending.put(token, new Entry(binding, clock.now() + TTL_MILLIS));
            return token;
        }
        return null;
    }

    synchronized boolean consume(String token, Binding presented) {
        if (token == null || token.isEmpty()) return false;
        long now = clock.now();
        cleanupExpiredLocked(now);
        Entry entry = pending.remove(token);
        return entry != null && entry.expiresAt > now && entry.binding.equals(presented);
    }

    synchronized int pendingCount() {
        cleanupExpiredLocked(clock.now());
        return pending.size();
    }

    private void cleanupExpiredLocked(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt <= now) iterator.remove();
        }
    }

    private static String randomToken(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        char[] encoded = new char[bytes.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            encoded[index * 2] = hex[value >>> 4];
            encoded[index * 2 + 1] = hex[value & 0x0f];
        }
        return new String(encoded);
    }

    static final class Binding {
        final int senderVuid;
        final int senderUserId;
        final String action;
        final String targetPackage;
        final long packageStopEpoch;
        final long userStopEpoch;

        Binding(int senderVuid, int senderUserId, String action, String targetPackage,
                LinePushStopFence.Permit stopPermit) {
            this.senderVuid = senderVuid;
            this.senderUserId = senderUserId;
            this.action = action;
            this.targetPackage = targetPackage;
            this.packageStopEpoch = stopPermit == null ? -1L : stopPermit.packageEpoch;
            this.userStopEpoch = stopPermit == null ? -1L : stopPermit.userEpoch;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Binding)) return false;
            Binding that = (Binding) other;
            return senderVuid == that.senderVuid
                    && senderUserId == that.senderUserId
                    && packageStopEpoch == that.packageStopEpoch
                    && userStopEpoch == that.userStopEpoch
                    && equal(action, that.action)
                    && equal(targetPackage, that.targetPackage);
        }

        @Override
        public int hashCode() {
            int result = senderVuid;
            result = 31 * result + senderUserId;
            result = 31 * result + Long.hashCode(packageStopEpoch);
            result = 31 * result + Long.hashCode(userStopEpoch);
            result = 31 * result + (action == null ? 0 : action.hashCode());
            return 31 * result + (targetPackage == null ? 0 : targetPackage.hashCode());
        }

        private static boolean equal(Object first, Object second) {
            return first == null ? second == null : first.equals(second);
        }
    }

    private static final class Entry {
        final Binding binding;
        final long expiresAt;

        Entry(Binding binding, long expiresAt) {
            this.binding = binding;
            this.expiresAt = expiresAt;
        }
    }
}
