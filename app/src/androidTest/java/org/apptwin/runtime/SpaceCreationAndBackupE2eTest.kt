package org.apptwin.runtime

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VPackageManager
import org.apptwin.AndroidMainOperations
import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.groups.FileGroupStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File

/** Fresh test-owned spaces; does not require clearing the user's AppTwin data. */
@RunWith(AndroidJUnit4::class)
class SpaceCreationAndBackupE2eTest {
    @Test fun googleIsRequiredAndBackupCreatesAnIndependentSpace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val owned = mutableListOf<String>()
        val backup = File(context.cacheDir, "feature-acceptance-${System.nanoTime()}.apptwin-space")
        var failure: Throwable? = null
        try {
            val original = operations.createGroup("備份驗收")
            owned += original.id
            val originalUser = requireNotNull(original.environmentBinding).internalId
            listOf("com.google.android.gms", "com.android.vending").forEach {
                assertNotNull("Required Google package $it", VPackageManager.get().getPackageInfo(it, 0, originalUser))
            }
            // Export a general space without LINE, while its source remains intact.
            operations.exportSpace(original.id, Uri.fromFile(backup), SpaceArchiveCompression.LOW)
            assertTrue(backup.length() > 0)
            val created = operations.createSpaceFromBackup(Uri.fromFile(backup)).group
            owned += created.id
            val createdUser = requireNotNull(created.environmentBinding).internalId
            assertNotEquals(original.id, created.id)
            assertNotEquals(originalUser, createdUser)
            assertNotNull(FileGroupStore(context).find(original.id))
            listOf("com.google.android.gms", "com.android.vending").forEach {
                assertNotNull("New space required Google package $it", VPackageManager.get().getPackageInfo(it, 0, createdUser))
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) { cleanupFailures += error }
            }
            // Remove the owned file through the production path so its registry entry is removed too.
            owned.forEach { id -> attempt {
                val entries = requireNotNull(operations.loadSpaceDetails(id)).backups
                    .filter { it.uri == Uri.fromFile(backup).toString() }
                entries.forEach { entry -> attempt { operations.deleteBackup(entry.id) } }
            } }
            // Export can fail before registry persistence; this exact private file is always test-owned.
            attempt { check(!backup.exists() || backup.delete()) { "Cannot remove test-owned backup file" } }
            owned.reversed().forEach { id -> attempt { operations.deleteGroup(id) } }
            if (failure != null) cleanupFailures.forEach { failure!!.addSuppressed(it) }
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }
}
