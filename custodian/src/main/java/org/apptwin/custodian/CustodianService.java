package org.apptwin.custodian;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import org.apptwin.custodian.contract.CustodianContract;
import org.apptwin.custodian.contract.ICustodianService;

public final class CustodianService extends Service {
    private CallerVerifier callerVerifier;
    private CustodianLedger ledger;

    private final ICustodianService.Stub serviceBinder = new ICustodianService.Stub() {
        @Override
        public int getProtocolVersion() {
            enforceTrustedCaller();
            return CustodianContract.PROTOCOL_VERSION;
        }

        @Override
        public int getHealthStatus() {
            enforceTrustedCaller();
            return ledger.isReady()
                    ? CustodianContract.HEALTH_READY
                    : CustodianContract.HEALTH_STORE_UNAVAILABLE;
        }

        @Override
        public String registerKeyspace(String spaceId, String packageName) {
            enforceTrustedCaller();
            return ledger.register(spaceId, packageName);
        }

        @Override
        public String resolveKeyspace(String spaceId, String packageName) {
            enforceTrustedCaller();
            return ledger.resolve(spaceId, packageName);
        }

        @Override
        public boolean transferKeyspace(
                String sourceSpaceId,
                String destinationSpaceId,
                String packageName,
                String keyspaceId) {
            enforceTrustedCaller();
            return ledger.transfer(sourceSpaceId, destinationSpaceId, packageName, keyspaceId);
        }

        @Override
        public boolean releaseKeyspace(String spaceId, String packageName, String keyspaceId) {
            enforceTrustedCaller();
            return ledger.release(spaceId, packageName, keyspaceId);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        callerVerifier = new CallerVerifier(this);
        ledger = new CustodianLedger(getFilesDir());
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !CustodianContract.ACTION_BIND.equals(intent.getAction())) {
            return null;
        }
        return serviceBinder;
    }

    private void enforceTrustedCaller() {
        enforceCallingPermission(
                CustodianContract.PERMISSION_BIND,
                "Caller does not hold the Custodian signature permission");
        if (!callerVerifier.isTrusted(Binder.getCallingUid())) {
            throw new SecurityException("Caller is not the trusted AppTwin host");
        }
    }
}
