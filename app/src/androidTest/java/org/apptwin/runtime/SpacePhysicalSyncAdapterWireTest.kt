package org.apptwin.runtime

import android.accounts.Account
import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.helper.compat.SyncAdapterWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded local fixture only; isolates platform wire from virtual service routing. */
@RunWith(AndroidJUnit4::class)
class SpacePhysicalSyncAdapterWireTest {
    @Test fun patchedFrameworkRejectsNonSystemRawSyncCaller() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("expectSystemSyncGuard") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var endpoint: IBinder? = null
        var result: SyncResult? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { endpoint = service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val syncContext = object : ISyncContext.Stub() {
            override fun sendHeartbeat() = Unit
            override fun onFinished(value: SyncResult?) { result = value; finished.countDown() }
        }
        val bound = context.bindService(Intent("android.content.SyncAdapter").setComponent(
            ComponentName("org.apptwin.fixture", "org.apptwin.fixture.FixtureSyncService")), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            assertTrue("Physical fixture binding", connected.await(10, TimeUnit.SECONDS))
            assertEquals("android.content.ISyncAdapter", requireNotNull(endpoint).interfaceDescriptor)
            SyncAdapterWire.start(requireNotNull(endpoint), syncContext.asBinder(), "org.apptwin.fixture.sync",
                Account("fixture-wire-only", "org.apptwin.fixture"), Bundle(), Build.VERSION.SDK_INT)
            assertFalse("Verified patched framework must reject this non-system caller", finished.await(3, TimeUnit.SECONDS))
            assertNull(result)
        } finally {
            endpoint?.let { runCatching { SyncAdapterWire.cancel(it, syncContext.asBinder(), Build.VERSION.SDK_INT) } }
            if (bound) context.unbindService(connection)
        }
    }
}
