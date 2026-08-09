package org.apptwin.gms.fixture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private app data is intentionally used so each AppTwin virtual user gets its own sentinel. */
public final class ProbeStore {
    private static final String FILE = "gms_capability_fixture";
    private static final String SENTINEL = "group_sentinel";
    private final SharedPreferences preferences;

    public ProbeStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public synchronized String getOrCreateSentinel() {
        String existing = preferences.getString(SENTINEL, null);
        if (existing != null) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        if (!preferences.edit().putString(SENTINEL, created).commit()) {
            throw new IllegalStateException("Unable to persist fixture sentinel");
        }
        return created;
    }

    public synchronized void save(ProbeResult result) {
        String prefix = result.id.name() + ".";
        boolean committed = preferences.edit()
                .putString(prefix + "status", result.status.name())
                .putString(prefix + "evidence", result.evidence)
                .putLong(prefix + "observed_at", result.observedAtEpochMillis)
                .commit();
        if (!committed) {
            throw new IllegalStateException("Unable to persist fixture probe result");
        }
    }

    public synchronized Map<ProbeId, ProbeResult> readAll() {
        Map<ProbeId, ProbeResult> results = new EnumMap<>(ProbeId.class);
        for (ProbeId id : ProbeId.values()) {
            String prefix = id.name() + ".";
            String status = preferences.getString(prefix + "status", null);
            if (status != null) {
                results.put(id, new ProbeResult(
                        id,
                        ProbeStatus.valueOf(status),
                        preferences.getString(prefix + "evidence", ""),
                        preferences.getLong(prefix + "observed_at", 0)));
            }
        }
        return results;
    }

    public synchronized Bundle toResultBundle() {
        Bundle result = new Bundle();
        result.putString("sentinel", getOrCreateSentinel());
        Map<ProbeId, ProbeResult> results = readAll();
        List<String> ids = new ArrayList<>();
        for (ProbeId id : ProbeId.values()) {
            ProbeResult probe = results.get(id);
            if (probe != null) {
                ids.add(id.name());
                result.putBundle("probe." + id.name(), probe.toBundle());
            }
        }
        result.putStringArrayList("probe_ids", new ArrayList<>(ids));
        return result;
    }
}
