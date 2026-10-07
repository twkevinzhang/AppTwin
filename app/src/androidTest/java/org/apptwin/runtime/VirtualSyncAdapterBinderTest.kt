package org.apptwin.runtime

import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lody.virtual.helper.compat.SyncAdapterWire
import com.lody.virtual.server.secondary.VirtualSyncAdapterBinder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualSyncAdapterBinderTest {
    @Test fun localIdentityIsRestoredWhenOriginalFrameworkDispatchThrows() {
        val originalUid = Binder.getCallingUid()
        var localUid = -1
        val base = object : Binder() {
            override fun getInterfaceDescriptor() = "android.content.ISyncAdapter"
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                localUid = Binder.getCallingUid()
                throw IllegalStateException("synthetic dispatch failure")
            }
        }
        val bridge = VirtualSyncAdapterBinder(base, android.os.Process.myPid())
        val data = Parcel.obtain()
        try {
            try { bridge.transact(SyncAdapterWire.startTransaction(Build.VERSION.SDK_INT), data, null, 0); fail("Expected local dispatch failure") }
            catch (expected: IllegalStateException) { }
            assertEquals(android.os.Process.SYSTEM_UID, localUid)
            assertEquals(originalUid, Binder.getCallingUid())
        } finally { data.recycle() }
    }
}
