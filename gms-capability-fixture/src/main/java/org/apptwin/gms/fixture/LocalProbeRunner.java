package org.apptwin.gms.fixture;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.gms.cast.CastRemoteDisplay;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;

import java.util.ArrayList;
import java.util.List;

/** Runs local-only probes. No probe sends a token, map, cast, billing, or integrity request. */
public final class LocalProbeRunner {
    private final Context context;
    private final ProbeStore store;

    public LocalProbeRunner(Context context) {
        this.context = context.getApplicationContext();
        this.store = new ProbeStore(this.context);
    }

    public List<ProbeResult> runAll() {
        List<ProbeResult> results = new ArrayList<>();
        results.add(run(ProbeId.PLAY_SERVICES_AVAILABILITY, this::availability));
        results.add(run(ProbeId.FCM_LOCAL_CONTRACT, this::fcmContract));
        results.add(run(ProbeId.ACCOUNT_SIGN_IN, this::accountAndSignIn));
        results.add(run(ProbeId.FUSED_LOCATION, this::location));
        results.add(run(ProbeId.MAPS_RENDERER, this::maps));
        results.add(run(ProbeId.CAST, this::cast));
        results.add(run(ProbeId.NEARBY, this::nearby));
        results.add(fixedUnsupported(
                ProbeId.PLAY_BILLING, "unsupported:no Play entitlement probe is executed"));
        results.add(fixedUnsupported(
                ProbeId.PLAY_INTEGRITY, "unsupported:no integrity request is executed"));
        for (ProbeResult result : results) {
            store.save(result);
        }
        return results;
    }

    private ProbeResult availability() {
        int code = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context);
        ProbeStatus status = code == ConnectionResult.SUCCESS
                ? ProbeStatus.LOCAL_PASS : ProbeStatus.LOCAL_ERROR;
        return result(ProbeId.PLAY_SERVICES_AVAILABILITY, status,
                "GoogleApiAvailability.code=" + code);
    }

    private ProbeResult fcmContract() throws PackageManager.NameNotFoundException {
        ComponentName service = new ComponentName(context, FixtureMessagingService.class);
        context.getPackageManager().getServiceInfo(service, PackageManager.GET_META_DATA);
        String sentinel = store.getOrCreateSentinel();
        TokenIsolationRecord record = new TokenIsolationRecord(
                context.getPackageName(), sentinel, "fixture-sender", "not-observed");
        NotificationRoute route = new NotificationRoute(
                MainActivity.class.getName(), sentinel);
        return result(ProbeId.FCM_LOCAL_CONTRACT, ProbeStatus.EXTERNAL_UNTESTED,
                "service=declared;scope=" + record.isolationKey()
                        + ";route=" + route.targetClassName + ";token=not-requested");
    }

    private ProbeResult accountAndSignIn() {
        String sentinel = store.getOrCreateSentinel();
        Account[] accounts = AccountManager.get(context).getAccounts();
        GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN);
        return result(ProbeId.ACCOUNT_SIGN_IN, ProbeStatus.EXTERNAL_UNTESTED,
                "account_api=present;visible_account_count=" + accounts.length
                        + ";sign_in_client=constructed;sentinel=" + sentinel);
    }

    private ProbeResult location() {
        LocationServices.getFusedLocationProviderClient(context);
        boolean fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        ProbeStatus status = fine || coarse
                ? ProbeStatus.LOCAL_READY : ProbeStatus.PERMISSION_DENIED;
        return result(ProbeId.FUSED_LOCATION, status,
                "client=constructed;fine=" + fine + ";coarse=" + coarse
                        + ";location_not_requested");
    }

    private ProbeResult maps() {
        MapsInitializer.Renderer rendererRequest = MapsInitializer.Renderer.LATEST;
        return result(ProbeId.MAPS_RENDERER, ProbeStatus.EXTERNAL_UNTESTED,
                "client_class=present;renderer_request=" + rendererRequest.name()
                        + ";initializer_not_called;api_key=absent");
    }

    private ProbeResult cast() {
        CastRemoteDisplay.getClient(context);
        return result(ProbeId.CAST, ProbeStatus.EXTERNAL_UNTESTED,
                "cast_client=constructed;receiver_not_configured;session_not_started");
    }

    private ProbeResult nearby() {
        Nearby.getConnectionsClient(context);
        return result(ProbeId.NEARBY, ProbeStatus.EXTERNAL_UNTESTED,
                "connections_client=constructed;advertising_and_discovery_not_started");
    }

    private ProbeResult run(ProbeId id, Probe probe) {
        try {
            return probe.execute();
        } catch (Exception | LinkageError failure) {
            return result(id, ProbeStatus.LOCAL_ERROR,
                    "local_probe_failed=" + failure.getClass().getSimpleName());
        }
    }

    private ProbeResult fixedUnsupported(ProbeId id, String evidence) {
        if (!CapabilityBoundary.isAlwaysUnsupported(id)) {
            throw new IllegalArgumentException("Probe is not fixed unsupported: " + id);
        }
        return result(id, ProbeStatus.UNSUPPORTED, evidence);
    }

    private ProbeResult result(ProbeId id, ProbeStatus status, String evidence) {
        return new ProbeResult(id, status, evidence, System.currentTimeMillis());
    }

    private boolean hasPermission(String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        return context.getPackageManager().checkPermission(permission, context.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    private interface Probe {
        ProbeResult execute() throws Exception;
    }
}
