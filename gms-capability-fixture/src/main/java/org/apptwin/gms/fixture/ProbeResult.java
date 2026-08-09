package org.apptwin.gms.fixture;

import android.os.Bundle;

import java.util.Objects;

public final class ProbeResult {
    public final ProbeId id;
    public final ProbeStatus status;
    public final String evidence;
    public final long observedAtEpochMillis;

    public ProbeResult(ProbeId id, ProbeStatus status, String evidence,
                       long observedAtEpochMillis) {
        this.id = Objects.requireNonNull(id);
        this.status = Objects.requireNonNull(status);
        this.evidence = Objects.requireNonNull(evidence);
        this.observedAtEpochMillis = observedAtEpochMillis;
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("id", id.name());
        bundle.putString("status", status.name());
        bundle.putString("evidence", evidence);
        bundle.putLong("observed_at", observedAtEpochMillis);
        return bundle;
    }
}
