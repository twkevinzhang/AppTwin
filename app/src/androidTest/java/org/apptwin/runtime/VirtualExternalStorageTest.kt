package org.apptwin.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.os.VEnvironment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualExternalStorageTest {
    @Test
    fun virtualStorageUsesWritableHostExternalFilesDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalFilesDir = requireNotNull(context.getExternalFilesDir(null))
        val virtualRoot = File(externalFilesDir, "virtual")
        val sharedStorage = requireNotNull(
            VEnvironment.getVirtualStorageDir(context.packageName, TEST_ENVIRONMENT_ID),
        )
        val privateStorage = requireNotNull(
            VEnvironment.getVirtualPrivateStorageDir(TEST_ENVIRONMENT_ID),
        )

        try {
            assertEquals(
                File(virtualRoot, "vsdcard/$TEST_ENVIRONMENT_ID").canonicalPath,
                sharedStorage.canonicalPath,
            )
            assertEquals(
                File(virtualRoot, TEST_ENVIRONMENT_ID.toString()).canonicalPath,
                privateStorage.canonicalPath,
            )
            assertTrue(sharedStorage.isDirectory)
            assertTrue(privateStorage.isDirectory)
            val sharedProbe = File(sharedStorage, "shared-probe").apply { writeText("shared") }
            val privateProbe = File(privateStorage, "private-probe").apply { writeText("private") }
            assertTrue(sharedProbe.isFile)
            assertTrue(privateProbe.isFile)
            assertEquals("shared", sharedProbe.readText())
            assertEquals("private", privateProbe.readText())
        } finally {
            sharedStorage.deleteRecursively()
            privateStorage.deleteRecursively()
        }
    }

    private companion object {
        const val TEST_ENVIRONMENT_ID = 91_731
    }
}
