package com.lody.virtual.client.hook.proxies.am;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;

public class DynamicReceiverForwarderTest {
    @Test
    public void callerReturnsBeforeSerialReceiverWorkRuns() {
        ManualExecutor executor = new ManualExecutor();
        DynamicReceiverForwarder forwarder = new DynamicReceiverForwarder(executor);
        List<Integer> delivered = new ArrayList<>();

        forwarder.submit(() -> delivered.add(1));
        forwarder.submit(() -> delivered.add(2));

        assertEquals(0, delivered.size());
        executor.drain();
        assertEquals(Arrays.asList(1, 2), delivered);
    }

    @Test
    public void receiverFailureDoesNotStrandLaterDelivery() {
        ManualExecutor executor = new ManualExecutor();
        DynamicReceiverForwarder forwarder = new DynamicReceiverForwarder(executor);
        List<Integer> delivered = new ArrayList<>();

        forwarder.submit(() -> { throw new IllegalStateException("broken receiver"); });
        forwarder.submit(() -> delivered.add(2));
        executor.drain();

        assertEquals(Arrays.asList(2), delivered);
        assertEquals(0, forwarder.pendingCount());
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void drain() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }
}
