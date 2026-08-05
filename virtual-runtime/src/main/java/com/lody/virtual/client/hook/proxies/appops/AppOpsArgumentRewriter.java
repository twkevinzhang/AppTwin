package com.lody.virtual.client.hook.proxies.appops;

/** Rewrites guest AppOps identity arguments to the real host identity. */
final class AppOpsArgumentRewriter {
    private AppOpsArgumentRewriter() {
    }

    static void rewrite(Object[] args, int uidIndex, int packageIndex,
                        int realUid, String hostPackage) {
        if (args == null) {
            return;
        }
        if (packageIndex >= 0 && packageIndex < args.length
                && args[packageIndex] instanceof String) {
            args[packageIndex] = hostPackage;
        }
        if (uidIndex >= 0 && uidIndex < args.length && args[uidIndex] instanceof Integer) {
            args[uidIndex] = realUid;
        }
    }
}
