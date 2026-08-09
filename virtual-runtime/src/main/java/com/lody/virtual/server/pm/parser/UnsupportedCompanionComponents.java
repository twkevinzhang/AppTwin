package com.lody.virtual.server.pm.parser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Removes product-declared unsupported Billing/Integrity entry points from pinned FakeStore. */
public final class UnsupportedCompanionComponents {
    private static final String COMPANION = "com.android.vending";
    private static final Set<String> DENIED_ACTIVITIES = new HashSet<>(Arrays.asList(
            "org.microg.vending.billing.PurchaseActivity",
            "org.microg.vending.billing.ui.InAppBillingHostActivity",
            "org.microg.vending.billing.ui.PlayWebViewActivity"));
    private static final Set<String> DENIED_SERVICES = new HashSet<>(Arrays.asList(
            "com.android.vending.billing.InAppBillingService",
            "com.google.android.finsky.integrityservice.IntegrityService",
            "com.google.android.finsky.expressintegrityservice.ExpressIntegrityService"));

    private UnsupportedCompanionComponents() {
    }

    public static void removeFrom(VPackage pkg) {
        if (pkg == null || !COMPANION.equals(pkg.packageName)) return;
        Iterator<VPackage.ActivityComponent> activities = pkg.activities.iterator();
        while (activities.hasNext()) {
            if (DENIED_ACTIVITIES.contains(activities.next().info.name)) activities.remove();
        }
        Iterator<VPackage.ServiceComponent> services = pkg.services.iterator();
        while (services.hasNext()) {
            if (DENIED_SERVICES.contains(services.next().info.name)) services.remove();
        }
    }

    static boolean isDenied(String packageName, String className, boolean service) {
        return COMPANION.equals(packageName)
                && (service ? DENIED_SERVICES : DENIED_ACTIVITIES).contains(className);
    }
}
