package com.lody.virtual.server.am;

/**
 * A service operation that must not be delivered until the guest Application is ready.
 *
 * <p>{@code CREATE_SERVICE} is deliberately absent from {@link Type}. Creating the first
 * service is what starts guest Application binding, so queuing it behind Application readiness
 * would deadlock the lifecycle.</p>
 */
final class PendingServiceOperation {

    enum Type {
        START_ARGS,
        BIND,
        REBIND,
        UNBIND,
        STOP,
        CONNECT
    }

    interface DispatchAction {
        void run() throws Exception;
    }

    interface CancellationListener {
        void onCancelled(ProcessLifecycle.TerminalReason reason);
    }

    private enum DeliveryState {
        PENDING,
        DISPATCHED,
        CANCELLED
    }

    private final long generation;
    private final Type type;
    private final String description;
    private final DispatchAction action;
    private final CancellationListener cancellationListener;
    private DeliveryState deliveryState = DeliveryState.PENDING;

    PendingServiceOperation(long generation, Type type, DispatchAction action) {
        this(generation, type, type.name(), action, null);
    }

    PendingServiceOperation(long generation, Type type, String description,
                            DispatchAction action,
                            CancellationListener cancellationListener) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        if (action == null) {
            throw new NullPointerException("action");
        }
        this.generation = generation;
        this.type = type;
        this.description = description == null ? type.name() : description;
        this.action = action;
        this.cancellationListener = cancellationListener;
    }

    long generation() {
        return generation;
    }

    Type type() {
        return type;
    }

    String description() {
        return description;
    }

    /** Returns false if a terminal transition cancelled the operation first. */
    boolean dispatch() throws Exception {
        synchronized (this) {
            if (deliveryState != DeliveryState.PENDING) {
                return false;
            }
            deliveryState = DeliveryState.DISPATCHED;
        }
        action.run();
        return true;
    }

    /** Cancellation is idempotent and never calls application code while holding this monitor. */
    boolean cancel(ProcessLifecycle.TerminalReason reason) {
        synchronized (this) {
            if (deliveryState != DeliveryState.PENDING) {
                return false;
            }
            deliveryState = DeliveryState.CANCELLED;
        }
        if (cancellationListener != null) {
            cancellationListener.onCancelled(reason);
        }
        return true;
    }

    synchronized boolean isDispatched() {
        return deliveryState == DeliveryState.DISPATCHED;
    }

    synchronized boolean isCancelled() {
        return deliveryState == DeliveryState.CANCELLED;
    }

    @Override
    public String toString() {
        return "PendingServiceOperation{" + type + ", generation=" + generation
                + ", description='" + description + "'}";
    }
}
