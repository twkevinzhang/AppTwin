package com.lody.virtual.server.am;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;

import com.lody.virtual.client.env.SpecialComponentList;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.TrustedGmsCloudMessagingState;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns the trusted microG Cloud Messaging lifecycle independently of the host UI.
 *
 * <p>The pinned microG release emits {@code org.microg.gms.gcm.CONNECTED} only after a
 * successful MCS login response. The virtual broadcast bridge preserves the virtual user id, so
 * this supervisor can use that event as a real connection signal while keeping state isolated per
 * user. Process and host-stub binding callbacks drive bounded recovery after a process death or
 * service disconnect.</p>
 */
final class TrustedGmsCloudMessagingSupervisor {
    static final String GMS_PACKAGE = "com.google.android.gms";
    static final String GMS_PERSISTENT_PROCESS = "com.google.android.gms:persistent";
    static final String PROVISION_SERVICE = "org.microg.gms.provision.ProvisionService";
    static final String MCS_SERVICE = "org.microg.gms.gcm.McsService";
    static final String ACTION_MCS_CONNECT = "org.microg.gms.gcm.mcs.CONNECT";
    static final String ACTION_GCM_CONNECTED = "org.microg.gms.gcm.CONNECTED";

    static final String FAILURE_NOT_INSTALLED = "NOT_INSTALLED";
    static final String FAILURE_START_REJECTED = "START_REJECTED";
    static final String FAILURE_CONNECT_TIMEOUT = "CONNECT_TIMEOUT";
    static final String FAILURE_MCS_RECONNECT_REQUIRED = "MCS_RECONNECT_REQUIRED";
    static final String FAILURE_MCS_RECONNECT_TIMEOUT = "MCS_RECONNECT_TIMEOUT";
    static final String FAILURE_PROCESS_DIED = "PROCESS_DIED";
    static final String FAILURE_BINDING_DISCONNECTED = "BINDING_DISCONNECTED";
    static final String FAILURE_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

    private static final String TAG = TrustedGmsCloudMessagingSupervisor.class.getSimpleName();
    private static final String EXTRA_VIRTUAL_USER_ID = "_VA_|_user_id_";
    private static final String EXTRA_ORIGINAL_INTENT = "_VA_|_intent_";
    private static final int MAX_RETRY_ATTEMPTS = 6;
    private static final long CONNECT_TIMEOUT_MS = 30_000L;
    private static final long[] RETRY_DELAYS_MS = {
            5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L
    };

    interface RuntimeOperations {
        boolean isInstalled(int userId);

        int[] installedUserIds();

        boolean startCloudMessaging(int userId);

        void stopCloudMessaging(int userId);

        boolean isPersistentProcessAlive(int userId);

        boolean isPersistentBindingAlive(int userId);
    }

    interface Cancellable {
        void cancel();
    }

    interface Scheduler {
        Cancellable schedule(Runnable runnable, long delayMillis);

        long currentTimeMillis();
    }

    interface DesiredUserStore {
        Set<Integer> load();

        boolean save(Set<Integer> userIds);
    }

    private enum PendingKind {
        NONE,
        SUPERVISOR_CONNECT_TIMEOUT,
        MCS_RECONNECT_CONFIRMATION,
        RETRY
    }

    private static final class UserState {
        boolean desired;
        int phase = TrustedGmsCloudMessagingState.PHASE_DISABLED;
        boolean processAlive;
        boolean bindingAlive;
        long lastConnectedAtMillis;
        int retryAttempt;
        long generation;
        long processGeneration = -1L;
        long connectionEpoch;
        String failureCode;
        Cancellable pendingWork;
        PendingKind pendingKind = PendingKind.NONE;

        TrustedGmsCloudMessagingState snapshot() {
            return new TrustedGmsCloudMessagingState(
                    phase,
                    processAlive,
                    bindingAlive,
                    lastConnectedAtMillis,
                    retryAttempt,
                    generation,
                    failureCode);
        }
    }

    private final RuntimeOperations runtime;
    private final Scheduler scheduler;
    private final DesiredUserStore desiredUserStore;
    private final Map<Integer, UserState> states = new HashMap<>();
    private final Set<Integer> durableDesiredUsers = new HashSet<>();
    /** Lock-free mirror of the durable authorization set used by daemon snapshots. */
    private volatile int durableDesiredUserCount;

    TrustedGmsCloudMessagingSupervisor(Context context, RuntimeOperations runtime,
            Handler handler) {
        this(runtime, new HandlerScheduler(handler),
                new SharedPreferencesDesiredUserStore(context));
        IntentFilter filter = new IntentFilter(
                SpecialComponentList.protectAction(ACTION_GCM_CONNECTED));
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                handleConnectedBroadcast(intent);
            }
        };
        String internalPermission = BroadcastSystem.internalBroadcastPermission(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The authenticated signal is emitted by the virtual microG process and consumed by
            // the engine process. Keep it cross-process addressable on API 33+ while requiring
            // the host-only signature permission so another application cannot forge CONNECTED.
            context.registerReceiver(receiver, filter, internalPermission, handler,
                    BroadcastSystem.receiverFlags());
        } else {
            context.registerReceiver(receiver, filter, internalPermission, handler);
        }
    }

    TrustedGmsCloudMessagingSupervisor(RuntimeOperations runtime, Scheduler scheduler) {
        this(runtime, scheduler, new InMemoryDesiredUserStore());
    }

    TrustedGmsCloudMessagingSupervisor(RuntimeOperations runtime, Scheduler scheduler,
            DesiredUserStore desiredUserStore) {
        this.runtime = runtime;
        this.scheduler = scheduler;
        this.desiredUserStore = desiredUserStore;
        Set<Integer> persisted = desiredUserStore.load();
        if (persisted != null) {
            for (Integer userId : persisted) {
                if (userId != null && userId > 0) durableDesiredUsers.add(userId);
            }
        }
        durableDesiredUserCount = durableDesiredUsers.size();
    }

    boolean ensureForUser(int userId) {
        if (userId <= 0) return false;
        synchronized (this) {
            Set<Integer> desired = new HashSet<>(durableDesiredUsers);
            desired.add(userId);
            if (!persistDesiredUsersLocked(desired)) return false;
        }
        return ensureDurableUser(userId);
    }

    boolean stopForUser(int userId) {
        synchronized (this) {
            Set<Integer> desired = new HashSet<>(durableDesiredUsers);
            desired.remove(userId);
            // Fail closed on persistence failure: do not diverge the live state from the durable
            // authorization that generic reconciliation will replay after a restart.
            if (!persistDesiredUsersLocked(desired)) return false;
            disableStateLocked(userId, null);
        }
        stopRuntimeOutsideLock(userId);
        return true;
    }

    boolean reconcile() {
        Set<Integer> desired;
        synchronized (this) {
            desired = new HashSet<>(durableDesiredUsers);
        }
        return reconcileDurableUsers(desired);
    }

    boolean reconcileDesiredUsers(int[] desiredUserIds) {
        Set<Integer> desired = new HashSet<>();
        if (desiredUserIds != null) {
            for (int userId : desiredUserIds) {
                if (userId > 0) desired.add(userId);
            }
        }
        synchronized (this) {
            if (!persistDesiredUsersLocked(desired)) return false;
        }
        return reconcileDurableUsers(desired);
    }

    /**
     * Reconciles a captured durable authorization without holding this monitor across runtime
     * calls. Every state commit rechecks the current durable set, so a newer exact allowlist wins.
     */
    private boolean reconcileDurableUsers(Set<Integer> requestedDesired) {
        int[] installedUsers = runtime.installedUserIds();
        Set<Integer> installed = new HashSet<>();
        if (installedUsers != null) {
            for (int userId : installedUsers) {
                if (userId > 0 && runtime.isInstalled(userId)) installed.add(userId);
            }
        }

        Set<Integer> currentDesired;
        Set<Integer> known;
        synchronized (this) {
            currentDesired = new HashSet<>(durableDesiredUsers);
            known = new HashSet<>(states.keySet());
        }
        // A newer exact reconciliation superseded this captured request. Do not apply stale
        // runtime operations; its caller will perform the authoritative pass.
        if (!currentDesired.equals(requestedDesired)) return false;

        boolean accepted = true;
        Set<Integer> candidates = new HashSet<>(installed);
        candidates.addAll(known);
        for (Integer userId : candidates) {
            if (!currentDesired.contains(userId)) {
                synchronized (this) {
                    if (!durableDesiredUsers.contains(userId)) {
                        disableStateLocked(userId, installed.contains(userId)
                                ? null : FAILURE_NOT_INSTALLED);
                    }
                }
                stopRuntimeOutsideLock(userId);
            }
        }
        for (Integer userId : currentDesired) {
            if (!installed.contains(userId)) {
                markDesiredUnavailable(userId);
                accepted = false;
            } else {
                accepted &= ensureDurableUser(userId);
            }
        }
        return accepted;
    }

    TrustedGmsCloudMessagingState getState(int userId) {
        boolean installed = runtime.isInstalled(userId);
        boolean processAlive = installed && runtime.isPersistentProcessAlive(userId);
        boolean bindingAlive = installed && runtime.isPersistentBindingAlive(userId);
        synchronized (this) {
            UserState state = states.get(userId);
            if (state == null) {
                if (!installed) {
                    return new TrustedGmsCloudMessagingState(
                            TrustedGmsCloudMessagingState.PHASE_DISABLED,
                            false, false, 0L, 0, 0L, FAILURE_NOT_INSTALLED);
                }
                return new TrustedGmsCloudMessagingState(
                        TrustedGmsCloudMessagingState.PHASE_UNKNOWN,
                        processAlive, bindingAlive,
                        0L, 0, 0L, null);
            }
            state.processAlive = processAlive;
            state.bindingAlive = bindingAlive;
            return state.snapshot();
        }
    }

    int desiredUserCount() {
        return durableDesiredUserCount;
    }

    synchronized boolean isDesiredUser(int userId) {
        return userId > 0 && durableDesiredUsers.contains(userId);
    }

    synchronized Set<Integer> durableDesiredUsers() {
        return new HashSet<>(durableDesiredUsers);
    }

    synchronized void onProcessReady(int userId, long processGeneration) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || processGeneration < state.processGeneration) {
            return;
        }
        if (state.processGeneration >= 0L && processGeneration > state.processGeneration
                && state.pendingKind == PendingKind.MCS_RECONNECT_CONFIRMATION) {
            cancelPending(state);
            state.connectionEpoch++;
        }
        state.processGeneration = processGeneration;
        state.processAlive = true;
        if (state.phase != TrustedGmsCloudMessagingState.PHASE_CONNECTED) {
            state.phase = TrustedGmsCloudMessagingState.PHASE_STARTING;
        }
    }

    synchronized void onProcessDied(int userId, long processGeneration) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || processGeneration != state.processGeneration) {
            return;
        }
        state.processAlive = false;
        state.bindingAlive = false;
        state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
        state.failureCode = FAILURE_PROCESS_DIED;
        scheduleRetryLocked(userId, state, state.generation);
    }

    synchronized void onBindingConnected(int userId, long processGeneration) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || processGeneration < state.processGeneration) {
            return;
        }
        if (state.processGeneration >= 0L && processGeneration > state.processGeneration
                && state.pendingKind == PendingKind.MCS_RECONNECT_CONFIRMATION) {
            cancelPending(state);
            state.connectionEpoch++;
        }
        state.processGeneration = processGeneration;
        state.processAlive = true;
        state.bindingAlive = true;
        if (state.phase != TrustedGmsCloudMessagingState.PHASE_CONNECTED) {
            state.phase = TrustedGmsCloudMessagingState.PHASE_STARTING;
        }
    }

    synchronized void onBindingDisconnected(int userId, long processGeneration) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || processGeneration != state.processGeneration) {
            return;
        }
        if (!state.processAlive) {
            return;
        }
        state.bindingAlive = false;
        state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
        state.failureCode = FAILURE_BINDING_DISCONNECTED;
        scheduleRetryLocked(userId, state, state.generation);
    }

    /**
     * Records the pinned microG persistent process' own decision that its MCS session must be
     * re-established. The caller and trigger metadata are authenticated before this method is
     * reached; process generation prevents a retired guest process from changing live state.
     */
    synchronized void onMcsReconnectRequired(int userId, long processGeneration,
            String triggerReason) {
        UserState state = states.get(userId);
        if (state == null || !state.desired
                || processGeneration != state.processGeneration
                || state.phase == TrustedGmsCloudMessagingState.PHASE_DISABLED) {
            return;
        }
        if (state.pendingKind == PendingKind.MCS_RECONNECT_CONFIRMATION) {
            // Repeated alarms/connectivity callbacks describe the same in-flight reconnect. Never
            // let them extend its fixed confirmation deadline.
            return;
        }
        if (state.phase != TrustedGmsCloudMessagingState.PHASE_CONNECTED) {
            return;
        }
        cancelPending(state);
        long generation = state.generation;
        long connectionEpoch = ++state.connectionEpoch;
        state.phase = TrustedGmsCloudMessagingState.PHASE_STARTING;
        state.failureCode = FAILURE_MCS_RECONNECT_REQUIRED;
        state.pendingKind = PendingKind.MCS_RECONNECT_CONFIRMATION;
        state.pendingWork = scheduler.schedule(
                () -> onMcsReconnectTimeout(userId, generation, processGeneration,
                        connectionEpoch),
                CONNECT_TIMEOUT_MS);
    }

    void onConnected(int userId) {
        boolean installed = runtime.isInstalled(userId);
        boolean processAlive = installed && runtime.isPersistentProcessAlive(userId);
        boolean bindingAlive = installed && runtime.isPersistentBindingAlive(userId);
        synchronized (this) {
            UserState state = states.get(userId);
            if (state == null || !state.desired || !installed) return;
            state.processAlive = processAlive;
            state.bindingAlive = bindingAlive;
            // The CONNECTED broadcast is accepted only while its per-user persistent process and
            // host keep-alive binding are both observable. This prevents an unrelated guest
            // broadcast from manufacturing a healthy state.
            if (!state.processAlive || !state.bindingAlive) return;
            cancelPending(state);
            state.connectionEpoch++;
            state.phase = TrustedGmsCloudMessagingState.PHASE_CONNECTED;
            state.lastConnectedAtMillis = scheduler.currentTimeMillis();
            state.retryAttempt = 0;
            state.failureCode = null;
        }
    }

    static long retryDelayMillis(int retryAttempt) {
        if (retryAttempt <= 0) {
            return RETRY_DELAYS_MS[0];
        }
        int index = Math.min(retryAttempt - 1, RETRY_DELAYS_MS.length - 1);
        return RETRY_DELAYS_MS[index];
    }

    private synchronized void onConnectTimeout(int userId, long generation,
            long connectionEpoch) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || state.generation != generation
                || state.connectionEpoch != connectionEpoch
                || state.pendingKind != PendingKind.SUPERVISOR_CONNECT_TIMEOUT
                || state.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED) {
            return;
        }
        state.pendingWork = null;
        state.pendingKind = PendingKind.NONE;
        state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
        state.failureCode = FAILURE_CONNECT_TIMEOUT;
        scheduleRetryLocked(userId, state, generation);
    }

    private void onMcsReconnectTimeout(int userId, long generation, long processGeneration,
            long connectionEpoch) {
        synchronized (this) {
            UserState state = states.get(userId);
            if (state == null || !state.desired || state.generation != generation
                    || state.processGeneration != processGeneration
                    || state.connectionEpoch != connectionEpoch
                    || state.pendingKind != PendingKind.MCS_RECONNECT_CONFIRMATION) {
                return;
            }
            state.pendingWork = null;
            state.pendingKind = PendingKind.NONE;
            state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
            state.failureCode = FAILURE_MCS_RECONNECT_TIMEOUT;
        }
        // microG's own reconnect was given one fixed confirmation window. Fall back immediately;
        // if this accepted start still cannot authenticate, its timeout enters the existing
        // bounded/backed-off retry sequence.
        attemptStart(userId, generation, connectionEpoch);
    }

    private void scheduleRetryLocked(int userId, UserState state, long generation) {
        cancelPending(state);
        if (!state.desired || state.generation != generation) {
            return;
        }
        if (state.retryAttempt >= MAX_RETRY_ATTEMPTS) {
            state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
            state.failureCode = FAILURE_RETRY_EXHAUSTED;
            return;
        }
        state.retryAttempt++;
        long delay = retryDelayMillis(state.retryAttempt);
        long connectionEpoch = ++state.connectionEpoch;
        state.pendingKind = PendingKind.RETRY;
        state.pendingWork = scheduler.schedule(
                () -> runRetry(userId, generation, connectionEpoch), delay);
    }

    private void runRetry(int userId, long generation, long connectionEpoch) {
        synchronized (this) {
            UserState state = states.get(userId);
            if (state == null || !state.desired || state.generation != generation
                    || state.connectionEpoch != connectionEpoch
                    || state.pendingKind != PendingKind.RETRY) return;
            state.pendingWork = null;
            state.pendingKind = PendingKind.NONE;
            state.phase = TrustedGmsCloudMessagingState.PHASE_STARTING;
        }
        attemptStart(userId, generation, connectionEpoch);
    }

    private boolean ensureDurableUser(int userId) {
        boolean installed = runtime.isInstalled(userId);
        if (!installed) {
            markDesiredUnavailable(userId);
            return false;
        }
        boolean processAlive = runtime.isPersistentProcessAlive(userId);
        boolean bindingAlive = runtime.isPersistentBindingAlive(userId);
        long generation;
        long connectionEpoch;
        synchronized (this) {
            if (!durableDesiredUsers.contains(userId)) return false;
            UserState state = stateFor(userId);
            if (!state.desired) {
                state.desired = true;
                state.generation++;
                state.retryAttempt = 0;
                state.failureCode = null;
            }
            state.processAlive = processAlive;
            state.bindingAlive = bindingAlive;
            if (state.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED
                    && processAlive && bindingAlive) {
                return true;
            }
            if (state.phase == TrustedGmsCloudMessagingState.PHASE_STARTING
                    && state.pendingKind == PendingKind.MCS_RECONNECT_CONFIRMATION
                    && processAlive && bindingAlive) {
                // microG is already executing the reconnect that produced this state. Generic
                // reconcile/ensure passes must not cancel its confirmation window or duplicate
                // the MCS start.
                return true;
            }
            cancelPending(state);
            connectionEpoch = ++state.connectionEpoch;
            state.phase = TrustedGmsCloudMessagingState.PHASE_STARTING;
            state.failureCode = null;
            generation = state.generation;
        }
        return attemptStart(userId, generation, connectionEpoch);
    }

    /** Performs all runtime calls outside the supervisor monitor, then commits by generation. */
    private boolean attemptStart(int userId, long generation, long connectionEpoch) {
        synchronized (this) {
            UserState state = states.get(userId);
            if (state == null || !state.desired || state.generation != generation
                    || state.connectionEpoch != connectionEpoch
                    || !durableDesiredUsers.contains(userId)) {
                return false;
            }
            state.phase = TrustedGmsCloudMessagingState.PHASE_STARTING;
        }

        boolean installed = runtime.isInstalled(userId);
        if (!installed) {
            markDesiredUnavailable(userId);
            return false;
        }
        boolean accepted;
        try {
            accepted = runtime.startCloudMessaging(userId);
        } catch (RuntimeException error) {
            VLog.e(TAG, "Unable to start trusted Cloud Messaging user=" + userId
                    + " error=" + error);
            accepted = false;
        }
        boolean processAlive = runtime.isPersistentProcessAlive(userId);
        boolean bindingAlive = runtime.isPersistentBindingAlive(userId);
        boolean unauthorized;
        boolean superseded;
        synchronized (this) {
            UserState state = states.get(userId);
            unauthorized = state == null || !state.desired || state.generation != generation
                    || !durableDesiredUsers.contains(userId);
            superseded = !unauthorized && state.connectionEpoch != connectionEpoch;
            if (!unauthorized && !superseded) {
                state.processAlive = processAlive;
                state.bindingAlive = bindingAlive;
                if (!accepted) {
                    state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
                    state.failureCode = FAILURE_START_REJECTED;
                    scheduleRetryLocked(userId, state, generation);
                } else {
                    state.pendingKind = PendingKind.SUPERVISOR_CONNECT_TIMEOUT;
                    state.pendingWork = scheduler.schedule(
                            () -> onConnectTimeout(userId, generation, connectionEpoch),
                            CONNECT_TIMEOUT_MS);
                }
            }
        }
        if (unauthorized && accepted) stopRuntimeOutsideLock(userId);
        return accepted && !unauthorized;
    }

    private void markDesiredUnavailable(int userId) {
        synchronized (this) {
            if (!durableDesiredUsers.contains(userId)) return;
            UserState state = stateFor(userId);
            if (!state.desired) {
                state.desired = true;
                state.generation++;
            }
            cancelPending(state);
            state.connectionEpoch++;
            state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
            state.processAlive = false;
            state.bindingAlive = false;
            state.retryAttempt = 0;
            state.failureCode = FAILURE_NOT_INSTALLED;
            state.processGeneration = -1L;
        }
    }

    private void stopRuntimeOutsideLock(int userId) {
        try {
            runtime.stopCloudMessaging(userId);
        } catch (RuntimeException error) {
            VLog.e(TAG, "Unable to stop trusted Cloud Messaging user=" + userId
                    + " error=" + error);
        }
        boolean desiredAgain;
        synchronized (this) {
            desiredAgain = durableDesiredUsers.contains(userId);
        }
        if (desiredAgain) ensureDurableUser(userId);
    }

    private void disableStateLocked(int userId, String failureCode) {
        UserState state = stateFor(userId);
        state.desired = false;
        state.generation++;
        cancelPending(state);
        state.connectionEpoch++;
        state.phase = TrustedGmsCloudMessagingState.PHASE_DISABLED;
        state.processAlive = false;
        state.bindingAlive = false;
        state.retryAttempt = 0;
        state.failureCode = failureCode;
        state.processGeneration = -1L;
    }

    private UserState stateFor(int userId) {
        UserState state = states.get(userId);
        if (state == null) {
            state = new UserState();
            states.put(userId, state);
        }
        return state;
    }

    private boolean persistDesiredUsersLocked(Set<Integer> desired) {
        Set<Integer> sanitized = new HashSet<>();
        for (Integer userId : desired) {
            if (userId != null && userId > 0) sanitized.add(userId);
        }
        if (!desiredUserStore.save(Collections.unmodifiableSet(sanitized))) {
            return false;
        }
        durableDesiredUsers.clear();
        durableDesiredUsers.addAll(sanitized);
        durableDesiredUserCount = sanitized.size();
        return true;
    }

    private static void cancelPending(UserState state) {
        if (state.pendingWork != null) {
            state.pendingWork.cancel();
            state.pendingWork = null;
        }
        state.pendingKind = PendingKind.NONE;
    }

    private void handleConnectedBroadcast(Intent redirected) {
        if (redirected == null) {
            return;
        }
        int userId = redirected.getIntExtra(EXTRA_VIRTUAL_USER_ID, -1);
        Intent original = redirected.getParcelableExtra(EXTRA_ORIGINAL_INTENT);
        if (userId < 0 || original == null
                || !ACTION_GCM_CONNECTED.equals(original.getAction())
                || !GMS_PACKAGE.equals(original.getPackage())) {
            return;
        }
        onConnected(userId);
    }

    private static final class HandlerScheduler implements Scheduler {
        private final Handler handler;

        HandlerScheduler(Handler handler) {
            this.handler = handler;
        }

        @Override
        public Cancellable schedule(Runnable runnable, long delayMillis) {
            handler.postDelayed(runnable, delayMillis);
            return () -> handler.removeCallbacks(runnable);
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

    private static final class SharedPreferencesDesiredUserStore implements DesiredUserStore {
        private static final String PREFERENCES = "trusted_gms_cloud_messaging_desired_users";
        private static final String KEY_USERS = "users";
        private final SharedPreferences preferences;

        SharedPreferencesDesiredUserStore(Context context) {
            preferences = context.getApplicationContext().getSharedPreferences(
                    PREFERENCES, Context.MODE_PRIVATE);
        }

        @Override
        public Set<Integer> load() {
            Set<Integer> result = new HashSet<>();
            try {
                for (String encoded : preferences.getStringSet(
                        KEY_USERS, Collections.emptySet())) {
                    int userId = Integer.parseInt(encoded);
                    if (userId > 0) result.add(userId);
                }
            } catch (RuntimeException unreadable) {
                VLog.e(TAG, "Unable to read trusted GMS desired users: " + unreadable);
                result.clear();
            }
            return result;
        }

        @Override
        public boolean save(Set<Integer> userIds) {
            Set<String> encoded = new HashSet<>();
            for (Integer userId : userIds) encoded.add(Integer.toString(userId));
            return preferences.edit().putStringSet(KEY_USERS, encoded).commit();
        }
    }

    private static final class InMemoryDesiredUserStore implements DesiredUserStore {
        private Set<Integer> users = new HashSet<>();

        @Override public Set<Integer> load() { return new HashSet<>(users); }

        @Override public boolean save(Set<Integer> userIds) {
            users = new HashSet<>(userIds);
            return true;
        }
    }
}
