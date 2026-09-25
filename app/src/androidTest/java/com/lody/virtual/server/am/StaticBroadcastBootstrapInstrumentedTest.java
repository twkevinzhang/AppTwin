package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.lody.virtual.client.IVClient;
import com.lody.virtual.remote.PendingResultData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Runs the production dispatcher with real Android scheduling and a fake guest transport.
 * No virtual space, real application, service binding, or message is created.
 */
@RunWith(AndroidJUnit4.class)
public class StaticBroadcastBootstrapInstrumentedTest {
    private FakeContext context;
    private ProcessRecord process;
    private StaticBroadcastDispatcher dispatcher;
    private ActivityInfo receiver;
    private final List<String> events = new ArrayList<>();
    private final List<Delivery> deliveries = new ArrayList<>();
    private final List<String> completions = new ArrayList<>();
    private final AtomicBoolean permitted = new AtomicBoolean(true);
    private int bootstrapCount;
    private final CountDownLatch firstCompletion = new CountDownLatch(1);

    @Before public void setUp() {
        context = new FakeContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "test.bootstrap.guest";
        process = new ProcessRecord(app, app.packageName, 10001, 0, 71);
        process.pkgList.add(app.packageName);
        receiver = new ActivityInfo();
        receiver.packageName = app.packageName;
        receiver.processName = app.packageName;
        receiver.name = app.packageName + ".Receiver";
        dispatcher = new StaticBroadcastDispatcher(context, new Handler(Looper.getMainLooper()),
                candidate -> candidate == process,
                (original, updated, reason) -> {
                    completions.add(reason);
                    firstCompletion.countDown();
                });
        // The dispatcher only uses asynchronous IVClient methods. A recording transport avoids
        // loading VClientImpl and therefore cannot initialize or affect an installed guest.
        process.client = (IVClient) Proxy.newProxyInstance(IVClient.class.getClassLoader(),
                new Class<?>[] { IVClient.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "bootstrapApplication":
                            assertEquals(process.info.packageName, args[0]);
                            assertEquals(process.processName, args[1]);
                            assertEquals(process.vuid, args[2]);
                            assertEquals(process.generation, args[3]);
                            assertNotNull(args[5]);
                            bootstrapCount++;
                            events.add("bootstrap");
                            break;
                        case "scheduleReceiver":
                            assertEquals(ProcessLifecycle.State.READY, process.lifecycle.state());
                            events.add(((Intent) args[2]).getAction());
                            deliveries.add(new Delivery((PendingResultData) args[3],
                                    (Long) args[4], (Long) args[5]));
                            break;
                        case "asBinder": return new Binder();
                        default: break;
                    }
                    return null;
                });
    }

    @After public void tearDown() {
        if (dispatcher != null) {
            dispatcher.cancelAll("test-cleanup");
            drainMain();
            assertEquals(0, dispatcher.pendingCount());
            assertEquals(context.connections.size(), context.unbound.size());
        }
    }

    @Test public void coldStartBootstrapsBeforeReadyThenDispatchesFifoOnce() {
        enqueue("first");
        enqueue("second");
        drainMain();
        assertEquals("STARTING must still initiate the thaw binding", 1, context.connections.size());
        connect(0);
        assertEquals(1, bootstrapCount);
        assertTrue(deliveries.isEmpty());
        assertEquals(2, dispatcher.pendingCount());
        // Another queue event during STARTING must not duplicate initialization.
        enqueue("third");
        drainMain();
        assertEquals(1, bootstrapCount);
        ready();
        assertEquals(1, deliveries.size());
        assertEquals("first", events.get(1));
        acknowledge(0);
        assertEquals("second", events.get(2));
        acknowledge(1);
        assertEquals("third", events.get(3));
        acknowledge(2);
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(3, completions.size());
        assertEquals(1, bootstrapCount);
    }

    @Test public void warmStartDispatchesWithoutBootstrap() {
        assertTrue(process.lifecycle.markReady(process.generation));
        enqueue("warm");
        drainMain();
        connect(0);
        assertEquals(0, bootstrapCount);
        assertEquals(1, deliveries.size());
        acknowledge(0);
        assertEquals("completed", completions.get(0));
    }

    @Test public void stopBeforeBindingCallbackPreventsBootstrapAndReceiver() {
        enqueue("stopped");
        drainMain();
        permitted.set(false);
        dispatcher.cancelProcess(process, "explicit-stop");
        drainMain();
        connect(0); // Simulate Android delivering a callback after unbind.
        ready();
        assertEquals(0, bootstrapCount);
        assertTrue(deliveries.isEmpty());
        assertEquals(1, completions.size());
        assertEquals("explicit-stop", completions.get(0));
    }

    @Test public void oldLeaseCallbackCannotCancelReplacementLease() {
        enqueue("old");
        drainMain();
        dispatcher.cancelProcess(process, "old-cancelled");
        drainMain();
        enqueue("replacement");
        drainMain();
        assertEquals(2, context.connections.size());
        context.connections.get(0).onServiceDisconnected(component());
        drainMain();
        assertEquals(1, dispatcher.pendingCount());
        connect(1);
        assertEquals(1, bootstrapCount);
        ready();
        assertEquals(1, deliveries.size());
        assertEquals("replacement", events.get(1));
        acknowledge(0);
        assertEquals(2, completions.size());
        assertEquals("completed", completions.get(1));
    }

    @Test public void bootstrapHardDeadlineUnbindsAndLateReadyCannotReviveDelivery()
            throws InterruptedException {
        enqueue("expires-before-ready");
        drainMain();
        connect(0);
        assertEquals(1, bootstrapCount);
        assertTrue(deliveries.isEmpty());
        // Exercise the real Handler deadline, not a policy clock or synthetic cancellation.
        assertTrue("bootstrap must finish within its hard deadline plus scheduling margin",
                firstCompletion.await(StaticBroadcastDispatcher.HARD_DEADLINE_MILLIS + 2_000,
                        TimeUnit.MILLISECONDS));
        drainMain();
        assertEquals("bootstrap-timeout", completions.get(0));
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(1, context.unbound.size());
        ready();
        connect(0);
        assertTrue(deliveries.isEmpty());
        assertEquals(1, bootstrapCount);
        assertEquals(1, completions.size());
    }

    @Test public void processDeathDuringBootstrapDrainsAndRejectsLateReady() {
        enqueue("dies-before-ready");
        drainMain();
        connect(0);
        assertEquals(1, bootstrapCount);
        assertTrue(process.lifecycle.markDead(process.generation,
                ProcessLifecycle.TerminalReason.PROCESS_DIED));
        dispatcher.cancelProcess(process, "process-died");
        drainMain();
        assertFalse(process.lifecycle.markReady(process.generation));
        dispatcher.onProcessReady(process);
        connect(0);
        drainMain();
        assertTrue(deliveries.isEmpty());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(1, context.unbound.size());
        assertEquals(1, completions.size());
        assertEquals("process-died", completions.get(0));
    }

    private void enqueue(String action) {
        assertTrue(dispatcher.enqueue(process, receiver, new Intent(action),
                pendingResult(), permitted::get));
    }

    private void ready() {
        assertTrue(process.lifecycle.markReady(process.generation));
        dispatcher.onProcessReady(process);
        drainMain();
    }

    private void connect(int index) {
        context.connections.get(index).onServiceConnected(component(), new Binder());
        drainMain();
    }

    private ComponentName component() {
        return new ComponentName(context.getPackageName(), "FakeBoundService");
    }

    private void acknowledge(int index) {
        Delivery delivery = deliveries.get(index);
        dispatcher.complete(delivery.generation, delivery.token, delivery.result);
        drainMain();
    }

    private static void drainMain() {
        // Nested callbacks in the production dispatcher post another main-loop operation.
        for (int i = 0; i < 3; i++) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { });
        }
    }

    private static PendingResultData pendingResult() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(0);
            parcel.writeByte((byte) 0);
            parcel.writeByte((byte) 0);
            parcel.writeStrongBinder(new Binder());
            parcel.writeInt(0);
            parcel.writeInt(0);
            parcel.writeInt(0);
            parcel.writeString(null);
            parcel.writeBundle(null);
            parcel.writeByte((byte) 0);
            parcel.writeByte((byte) 0);
            parcel.setDataPosition(0);
            return PendingResultData.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static final class Delivery {
        final PendingResultData result;
        final long token;
        final long generation;
        Delivery(PendingResultData result, long token, long generation) {
            this.result = result;
            this.token = token;
            this.generation = generation;
        }
    }

    private static final class FakeContext extends ContextWrapper {
        final List<ServiceConnection> connections = new ArrayList<>();
        final List<ServiceConnection> unbound = new ArrayList<>();
        FakeContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            connections.add(connection);
            return true;
        }
        @Override public void unbindService(ServiceConnection connection) {
            assertFalse("each lease must unbind exactly once", unbound.contains(connection));
            unbound.add(connection);
        }
    }
}
