package com.lody.virtual.server.am;

/**
 * Stable identity of one logical guest process.
 *
 * <p>The same guest package may legitimately own more than one process, and the same package may
 * run for more than one virtual uid. All three fields are therefore part of the identity.</p>
 */
final class LogicalProcessKey {

    private final int vuid;
    private final String packageName;
    private final String processName;

    LogicalProcessKey(int vuid, String packageName, String processName) {
        if (vuid < 0) {
            throw new IllegalArgumentException("vuid must be non-negative");
        }
        this.vuid = vuid;
        this.packageName = requireName(packageName, "packageName");
        this.processName = requireName(processName, "processName");
    }

    int vuid() {
        return vuid;
    }

    String packageName() {
        return packageName;
    }

    String processName() {
        return processName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LogicalProcessKey)) {
            return false;
        }
        LogicalProcessKey that = (LogicalProcessKey) other;
        return vuid == that.vuid
                && packageName.equals(that.packageName)
                && processName.equals(that.processName);
    }

    @Override
    public int hashCode() {
        int result = vuid;
        result = 31 * result + packageName.hashCode();
        result = 31 * result + processName.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "LogicalProcessKey{"
                + "vuid=" + vuid
                + ", packageName='" + packageName + '\''
                + ", processName='" + processName + '\''
                + '}';
    }

    private static String requireName(String value, String label) {
        if (value == null) {
            throw new NullPointerException(label);
        }
        if (value.length() == 0) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }
}
