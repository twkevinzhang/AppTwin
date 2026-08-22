package com.lody.virtual.server.am;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import com.lody.virtual.client.env.SpecialComponentList;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.TrustedGmsCloudMessagingState;

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

    private static final class UserState {
        boolean desired;
        int phase = TrustedGmsCloudMessagingState.PHASE_DISABLED;
        boolean processAlive;
        boolean bindingAlive;
        long lastConnectedAtMillis;
        int retryAttempt;
        long generation;
        long processGeneration = -1L;
        String failureCode;
        Cancellable pendingWork;

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
    private final Map<Integer, UserState> states = new HashMap<>();

    TrustedGmsCloudMessagingSupervisor(Context context, RuntimeOperations runtime,
            Handler handler) {
        this(runtime, new HandlerScheduler(handler));
        IntentFilter filter = new IntentFilter(
                SpecialComponentList.protectAction(ACTION_GCM_CONNECTED));
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                handleConnectedBroadcast(intent);
            }
        }, filter, null, handler);
    }

    TrustedGmsCloudMessagingSupervisor(RuntimeOperations runtime, Scheduler scheduler) {
        this.runtime = runtime;
        this.scheduler = scheduler;
    }

    synchronized boolean ensureForUser(int userId) {
        if (userId < 0 || !runtime.isInstalled(userId)) {
            stopInternal(userId, FAILURE_NOT_INSTALLED, false);
            return false;
        }
        UserState state = stateFor(userId);
        if (!state.desired) {
            state.desired = true;
            state.generation++;
            state.retryAttempt = 0;
            state.failureCode = null;
        }
        refreshObservedState(userId, state);
        if (state.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED
                && state.processAlive && state.bindingAlive) {
            return true;
        }
        cancelPending(state);
        return attemptStartLocked(userId, state, state.generation);
    }

    synchronized boolean stopForUser(int userId) {
        stopInternal(userId, null, true);
        return true;
    }

    synchronized void reconcile() {
        int[] installedUsers = runtime.installedUserIds();
        Set<Integer> installed = new HashSet<>();
        if (installedUsers != null) {
            for (int userId : installedUsers) {
                if (userId < 0 || !runtime.isInstalled(userId)) {
                    continue;
                }
                installed.add(userId);
                ensureForUser(userId);
            }
        }
        for (Integer userId : new HashSet<>(states.keySet())) {
            if (!installed.contains(userId)) {
                stopInternal(userId, FAILURE_NOT_INSTALLED, true);
            }
        }
    }

    synchronized TrustedGmsCloudMessagingState getState(int userId) {
        UserState state = states.get(userId);
        if (state == null) {
            if (!runtime.isInstalled(userId)) {
                return new TrustedGmsCloudMessagingState(
                        TrustedGmsCloudMessagingState.PHASE_DISABLED,
                        false, false, 0L, 0, 0L, FAILURE_NOT_INSTALLED);
            }
            return new TrustedGmsCloudMessagingState(
                    TrustedGmsCloudMessagingState.PHASE_UNKNOWN,
                    runtime.isPersistentProcessAlive(userId),
                    runtime.isPersistentBindingAlive(userId),
                    0L, 0, 0L, null);
        }
        refreshObservedState(userId, state);
        return state.snapshot();
    }

    synchronized void onProcessReady(int userId, long processGeneration) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || processGeneration < state.processGeneration) {
            return;
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

    synchronized void onConnected(int userId) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || !runtime.isInstalled(userId)) {
            return;
        }
        refreshObservedState(userId, state);
        // The CONNECTED broadcast is accepted only while its per-user persistent process and host
        // keep-alive binding are both observable. This prevents an unrelated guest broadcast from
        // manufacturing a healthy state.
        if (!state.processAlive || !state.bindingAlive) {
            return;
        }
        cancelPending(state);
        state.phase = TrustedGmsCloudMessagingState.PHASE_CONNECTED;
        state.lastConnectedAtMillis = scheduler.currentTimeMillis();
        state.retryAttempt = 0;
        state.failureCode = null;
    }

    static long retryDelayMillis(int retryAttempt) {
        if (retryAttempt <= 0) {
            return RETRY_DELAYS_MS[0];
        }
        int index = Math.min(retryAttempt - 1, RETRY_DELAYS_MS.length - 1);
        return RETRY_DELAYS_MS[index];
    }

    private boolean attemptStartLocked(int userId, UserState state, long generation) {
        if (!state.desired || state.generation != generation || !runtime.isInstalled(userId)) {
            return false;
        }
        state.phase = TrustedGmsCloudMessagingState.PHASE_STARTING;
        state.failureCode = null;
        boolean accepted;
        try {
            accepted = runtime.startCloudMessaging(userId);
        } catch (RuntimeException error) {
            VLog.e(TAG, "Unable to start trusted Cloud Messaging user=" + userId
                    + " error=" + error);
            accepted = false;
        }
        refreshObservedState(userId, state);
        if (!accepted) {
            state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
            state.failureCode = FAILURE_START_REJECTED;
            scheduleRetryLocked(userId, state, generation);
            return false;
        }
        state.pendingWork = scheduler.schedule(
                () -> onConnectTimeout(userId, generation), CONNECT_TIMEOUT_MS);
        return true;
    }

    private synchronized void onConnectTimeout(int userId, long generation) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || state.generation != generation
                || state.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED) {
            return;
        }
        state.pendingWork = null;
        refreshObservedState(userId, state);
        state.phase = TrustedGmsCloudMessagingState.PHASE_DEGRADED;
        state.failureCode = FAILURE_CONNECT_TIMEOUT;
        scheduleRetryLocked(userId, state, generation);
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
        state.pendingWork = scheduler.schedule(() -> runRetry(userId, generation), delay);
    }

    private synchronized void runRetry(int userId, long generation) {
        UserState state = states.get(userId);
        if (state == null || !state.desired || state.generation != generation) {
            return;
        }
        state.pendingWork = null;
        if (!runtime.isInstalled(userId)) {
            stopInternal(userId, FAILURE_NOT_INSTALLED, true);
            return;
        }
        attemptStartLocked(userId, state, generation);
    }

    private boolean stopInternal(int userId, String failureCode, boolean callRuntime) {
        UserState state = stateFor(userId);
        boolean wasDesired = state.desired;
        state.desired = false;
        state.generation++;
        cancelPending(state);
        if (callRuntime) {
            try {
                runtime.stopCloudMessaging(userId);
            } catch (RuntimeException error) {
                VLog.e(TAG, "Unable to stop trusted Cloud Messaging user=" + userId
                        + " error=" + error);
            }
        }
        state.phase = TrustedGmsCloudMessagingState.PHASE_DISABLED;
        state.processAlive = false;
        state.bindingAlive = false;
        state.retryAttempt = 0;
        state.failureCode = failureCode;
        state.processGeneration = -1L;
        return wasDesired;
    }

    private void refreshObservedState(int userId, UserState state) {
        state.processAlive = runtime.isPersistentProcessAlive(userId);
        state.bindingAlive = runtime.isPersistentBindingAlive(userId);
    }

    private UserState stateFor(int userId) {
        UserState state = states.get(userId);
        if (state == null) {
            state = new UserState();
            states.put(userId, state);
        }
        return state;
    }

    private static void cancelPending(UserState state) {
        if (state.pendingWork != null) {
            state.pendingWork.cancel();
            state.pendingWork = null;
        }
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
}
