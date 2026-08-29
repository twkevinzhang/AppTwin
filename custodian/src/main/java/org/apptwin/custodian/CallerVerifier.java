package org.apptwin.custodian;

import android.content.Context;
import android.content.pm.PackageManager;

import org.apptwin.custodian.contract.CustodianContract;

final class CallerVerifier {
    private final Context context;

    CallerVerifier(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isTrusted(int callingUid) {
        PackageManager packageManager = context.getPackageManager();
        try {
            int hostUid = packageManager.getPackageUid(CustodianContract.HOST_PACKAGE, 0);
            boolean signaturesMatch = packageManager.checkSignatures(
                    CustodianContract.HOST_PACKAGE,
                    context.getPackageName()) == PackageManager.SIGNATURE_MATCH;
            return CallerTrustPolicy.isTrusted(callingUid, hostUid, signaturesMatch);
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
}
