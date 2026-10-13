package com.lody.virtual.client.hook.proxies.am;

/** Routes legacy and device-aware AM permission queries without inheriting a host grant. */
final class GuestPermissionCheck {
    interface VirtualDecision {
        int check(String permission, String packageName, int userId) throws Throwable;
    }

    interface HostDecision {
        Object check(Object[] args) throws Throwable;
    }

    static Object dispatch(String methodName, Object[] args, boolean controlled,
                           String packageName, int userId, int hostUid,
                           VirtualDecision virtual, HostDecision host) throws Throwable {
        boolean valid = validSignature(methodName, args);
        if (controlled) {
            if (!valid || packageName == null || packageName.isEmpty() || userId < 0) return -1;
            return virtual.check((String) args[0], packageName, userId);
        }
        if (valid) {
            // Both signatures put UID at index 2. A device id is never a UID.
            args[2] = hostUid;
        }
        return host.check(args);
    }

    private static boolean validSignature(String methodName, Object[] args) {
        int count = "checkPermission".equals(methodName) ? 3
                : "checkPermissionForDevice".equals(methodName) ? 4 : -1;
        if (args == null || args.length != count || !(args[0] instanceof String)
                || !(args[1] instanceof Integer) || !(args[2] instanceof Integer)) return false;
        return count == 3 || args[3] instanceof Integer;
    }

    private GuestPermissionCheck() {}
}
