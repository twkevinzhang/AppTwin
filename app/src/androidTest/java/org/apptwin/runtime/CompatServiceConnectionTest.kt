package org.apptwin.runtime

import android.content.ComponentName
import android.os.Binder
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lody.virtual.helper.compat.CompatServiceConnection
import com.lody.virtual.helper.compat.ServiceConnectionCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompatServiceConnectionTest {
    @Test fun transportDispatchesBootWireAndDeadFlagWithoutFrameworkStub() {
        val expected = ComponentName("org.apptwin.fixture", "FixtureAccountService")
        val endpoint = Binder()
        var calls = 0
        var lastDead = false
        val connection = object : CompatServiceConnection() {
            override fun onConnected(name: ComponentName?, service: IBinder?, dead: Boolean) {
                assertEquals(expected, name)
                assertSame(endpoint, service)
                lastDead = dead
                calls++
            }
        }
        ServiceConnectionCompat.connected(connection, expected, endpoint, true)
        assertEquals(1, calls)
        assertTrue(lastDead)
        connection.connected(expected, endpoint)
        assertEquals(2, calls)
        assertFalse(lastDead)
        connection.connected(expected, endpoint, true)
        assertEquals(3, calls)
        assertTrue(lastDead)
    }
}
