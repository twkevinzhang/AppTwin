package com.lody.virtual.client.hook.proxies.telecom;

import com.lody.virtual.client.stub.StubConnectionService;
import com.lody.virtual.client.stub.VASettings;

/** Narrow identity and routing policy for LINE's self-managed Telecom account. */
public final class TelecomPhoneAccountPolicy {
    static final String LINE_PACKAGE = "jp.naver.line.android";
    static final String LINE_CONNECTION_SERVICE =
            "com.linecorp.andromeda.core.AndromedaConnectionService";
    private static final String ID_PREFIX = "apptwin:telecom:1:";

    private TelecomPhoneAccountPolicy() {
    }

    public static boolean supports(String packageName, String className) {
        return LINE_PACKAGE.equals(packageName) && LINE_CONNECTION_SERVICE.equals(className);
    }

    public static boolean canRoute(Identity identity, String currentPackage, int currentUserId) {
        return identity != null
                && identity.virtualUserId == currentUserId
                && identity.packageName.equals(currentPackage)
                && supports(identity.packageName, identity.className);
    }

    public static String stubClassName(int slot) {
        if (slot < 0 || slot >= VASettings.STUB_COUNT) {
            return null;
        }
        return StubConnectionService.class.getName() + "$C" + slot;
    }

    public static int parseStubSlot(String hostPackage, String processName) {
        if (hostPackage == null || processName == null) {
            return -1;
        }
        String prefix = hostPackage + ":p";
        if (!processName.startsWith(prefix)) {
            return -1;
        }
        try {
            int slot = Integer.parseInt(processName.substring(prefix.length()));
            return slot >= 0 && slot < VASettings.STUB_COUNT ? slot : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static String encode(int virtualUserId, String packageName, String className,
                                String originalId) {
        if (virtualUserId < 0 || packageName == null || className == null || originalId == null) {
            return null;
        }
        return ID_PREFIX + virtualUserId + ":"
                + field(packageName) + field(className) + field(originalId);
    }

    public static Identity decode(String encoded) {
        if (encoded == null || !encoded.startsWith(ID_PREFIX)) {
            return null;
        }
        try {
            Cursor cursor = new Cursor(encoded, ID_PREFIX.length());
            int userSeparator = encoded.indexOf(':', cursor.position);
            if (userSeparator < 0) {
                return null;
            }
            int userId = Integer.parseInt(encoded.substring(cursor.position, userSeparator));
            if (userId < 0) {
                return null;
            }
            cursor.position = userSeparator + 1;
            String packageName = cursor.readField();
            String className = cursor.readField();
            String originalId = cursor.readField();
            if (cursor.position != encoded.length()) {
                return null;
            }
            return new Identity(userId, packageName, className, originalId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }

    public static final class Identity {
        public final int virtualUserId;
        public final String packageName;
        public final String className;
        public final String originalId;

        Identity(int virtualUserId, String packageName, String className, String originalId) {
            this.virtualUserId = virtualUserId;
            this.packageName = packageName;
            this.className = className;
            this.originalId = originalId;
        }
    }

    private static final class Cursor {
        private final String value;
        private int position;

        Cursor(String value, int position) {
            this.value = value;
            this.position = position;
        }

        String readField() {
            int separator = value.indexOf(':', position);
            if (separator < 0) {
                throw new IllegalArgumentException("Missing field length");
            }
            int length = Integer.parseInt(value.substring(position, separator));
            if (length < 0) {
                throw new IllegalArgumentException("Negative field length");
            }
            int start = separator + 1;
            int end = start + length;
            if (end < start || end > value.length()) {
                throw new IllegalArgumentException("Field exceeds input");
            }
            position = end;
            return value.substring(start, end);
        }
    }
}
