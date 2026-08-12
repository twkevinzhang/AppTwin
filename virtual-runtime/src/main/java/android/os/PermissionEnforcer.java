package android.os;

import android.content.AttributionSource;

/**
 * Compile-time surface for the hidden framework class. Android always loads the boot-classpath
 * implementation at runtime; this declaration only lets the runtime bridge provide a constrained
 * subclass without bundling or replacing framework code.
 */
public class PermissionEnforcer {
    protected PermissionEnforcer() {}

    protected int checkPermission(String permission, AttributionSource source) {
        return 2;
    }

    protected int checkPermission(String permission, int pid, int uid) {
        return 2;
    }
}
