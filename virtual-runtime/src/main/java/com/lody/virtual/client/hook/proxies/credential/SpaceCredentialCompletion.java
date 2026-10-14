package com.lody.virtual.client.hook.proxies.credential;

import java.util.concurrent.atomic.AtomicBoolean;

/** One terminal delivery across cancellation, timeout, callback death and activity results. */
final class SpaceCredentialCompletion {
    private final AtomicBoolean finished = new AtomicBoolean();

    boolean isFinished() { return finished.get(); }

    boolean finish(Runnable terminalDelivery) {
        if (!finished.compareAndSet(false, true)) return false;
        terminalDelivery.run();
        return true;
    }
}
