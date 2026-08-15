package com.lody.virtual.server.am;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure lifecycle state for one temporarily retained cloned-LINE process generation. */
final class LinePushProcessProtection {
    enum EnqueueAction {
        START_BIND,
        WAIT_FOR_BIND,
        DISPATCH_NOW
    }

    private enum State {
        NEW,
        BINDING,
        RETAINED,
        RELEASED
    }

    private final Map<Object, PendingDispatch> dispatches = new LinkedHashMap<>();
    private State state = State.NEW;

    synchronized EnqueueAction enqueue(Object token, Runnable dispatch, Runnable abort) {
        if (token == null || dispatch == null || abort == null || state == State.RELEASED) {
            throw new IllegalArgumentException("Active protection and non-null arguments required");
        }
        PendingDispatch pending = new PendingDispatch(dispatch, abort);
        dispatches.put(token, pending);
        if (state == State.NEW) {
            state = State.BINDING;
            return EnqueueAction.START_BIND;
        }
        if (state == State.BINDING) {
            return EnqueueAction.WAIT_FOR_BIND;
        }
        pending.dispatched = true;
        return EnqueueAction.DISPATCH_NOW;
    }

    synchronized List<Runnable> onConnected() {
        if (state != State.BINDING) {
            return new ArrayList<>();
        }
        state = State.RETAINED;
        List<Runnable> ready = new ArrayList<>();
        for (PendingDispatch pending : dispatches.values()) {
            if (!pending.dispatched) {
                pending.dispatched = true;
                ready.add(pending.dispatch);
            }
        }
        return ready;
    }

    synchronized boolean complete(Object token) {
        dispatches.remove(token);
        return state == State.RETAINED && dispatches.isEmpty();
    }

    synchronized boolean releaseIfIdle() {
        if (state != State.RETAINED || !dispatches.isEmpty()) {
            return false;
        }
        state = State.RELEASED;
        return true;
    }

    synchronized List<Runnable> terminate() {
        if (state == State.RELEASED) {
            return new ArrayList<>();
        }
        state = State.RELEASED;
        List<Runnable> aborts = new ArrayList<>();
        for (PendingDispatch pending : dispatches.values()) {
            if (!pending.dispatched) {
                aborts.add(pending.abort);
            }
        }
        dispatches.clear();
        return aborts;
    }

    synchronized List<Object> tokens() {
        return new ArrayList<>(dispatches.keySet());
    }

    synchronized int dispatchCount() {
        return dispatches.size();
    }

    synchronized boolean isRetained() {
        return state == State.RETAINED;
    }

    private static final class PendingDispatch {
        final Runnable dispatch;
        final Runnable abort;
        boolean dispatched;

        PendingDispatch(Runnable dispatch, Runnable abort) {
            this.dispatch = dispatch;
            this.abort = abort;
        }
    }
}
