package org.apptwin.runtime

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real contacts-only provider isolation. Host grants are prepared/restored externally. */
@RunWith(AndroidJUnit4::class)
class OwnedContactsProviderE2eTest {
    @Test
    fun actualLocalContactsReadWriteStayScopedAcrossRestartAndRevocation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach {
            assertEquals("External host contacts grant prerequisite", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(it))
        }
        val app = context.applicationContext as AppTwinApplication
        val operations = app.mainContainer.operationGraph.mainOperations()
        val store = FileGroupStore(context)
        val baseline = tuples(store.loadSnapshot().groups)
        val owned = mutableListOf<String>()
        val markers = mutableListOf(UUID.randomUUID().toString())
        val seedMarker = markers.single()
        var seedId: Long? = null
        val journal = File(context.cacheDir, "owned-contacts-${UUID.randomUUID()}.json")
        fun persistOwnership() {
            val temporary = File(journal.path + ".tmp")
            temporary.writeText(JSONObject().put("markers", JSONArray(markers)).put("groups", JSONArray(owned)).toString())
            check(temporary.renameTo(journal))
        }
        persistOwnership()
        var primary: Throwable? = null
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            val revision = AndroidPackageRevisionImporter(context).sync(PACKAGE)
            assertTrue(revision is RevisionImportResult.Activated || revision is RevisionImportResult.AlreadyCurrent)
            val seed = requireNotNull(context.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
                putNull(ContactsContract.RawContacts.ACCOUNT_NAME); putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                put(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED)
                put(ContactsContract.RawContacts.SOURCE_ID, seedMarker)
            }))
            seedId = ContentUris.parseId(seed)
            val local = context.contentResolver.query(seed, arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE), null, null, null)?.use { it.moveToFirst() && it.isNull(0) && it.isNull(1) } ?: false
            assertTrue("Synthetic seed must remain unassociated local row", local)
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(seedId.toString()), null)?.use { assertEquals(0, it.count) }
                ?: error("Unable to verify owned seed has no Data")
            val a = operations.createGroup("Owned contacts A").also { owned += it.id; persistOwnership() }
            val b = operations.createGroup("Owned contacts B").also { owned += it.id; persistOwnership() }
            val preparedA = operations.addAppToGroup(a.id, PACKAGE)
            val preparedB = operations.addAppToGroup(b.id, PACKAGE)
            assertNotEquals(preparedA.user(), preparedB.user())
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach { permission ->
                assertTrue(operations.setClonePermission(preparedA.item(), permission, true))
                assertTrue(operations.setClonePermission(preparedB.item(), permission, false))
            }
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                fun probe(group: Group, allowed: Boolean) {
                    VirtualCore.get().killApp(PACKAGE, group.user())
                    val request = UUID.randomUUID().toString()
                    markers += request; persistOwnership()
                    val files = File(VEnvironment.getDataUserPackageDirectory(group.user(), PACKAGE), "files")
                    check(files.isDirectory || files.mkdirs())
                    File(files, "enable-owned-contacts-probe").writeText(JSONObject().put("requestId", request)
                        .put("seedMarker", seedMarker).put("seedId", requireNotNull(seedId)).toString())
                    val result = File(files, "owned-contacts-outcomes.json")
                    check(!result.exists() || result.delete())
                    host.awaitRealForegroundHost()
                    val launch = runBlocking { operations.launchGroupApp(group.item()) }
                    assertTrue("Exact owned contacts fixture launch", launch is RuntimeLaunchResult.Started)
                    launch as RuntimeLaunchResult.Started
                    assertEquals(PACKAGE, launch.packageName)
                    assertEquals(files.parentFile!!.absolutePath, launch.dataDirectory)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                    var outcome: JSONObject? = null
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        outcome = runCatching { JSONObject(result.readText()) }.getOrNull()
                        if (outcome?.optString("requestId") == request) break
                        Thread.sleep(100)
                    }
                    val observed = requireNotNull(outcome) { "Owned contacts probe timed out" }
                    assertEquals("Fresh probe invocation", request, observed.optString("requestId"))
                    val expected = if (allowed) "allowed" else "blocked:SecurityException"
                    assertEquals(expected, observed.getString("contactsRead"))
                    assertEquals(expected, observed.getString("contactsWrite"))
                    if (allowed) assertTrue("Guest exact insert cleanup completed", observed.getBoolean("writeCleanup"))
                    val lifecycle = JSONObject(File(files, "fixture-lifecycle.json").readText())
                    assertTrue(lifecycle.getBoolean("resumed")); assertTrue(lifecycle.getBoolean("focused"))
                    val task = VActivityManager.get().getTaskInfo(lifecycle.getInt("task"))
                    assertEquals(group.user(), task?.userId); assertEquals(PACKAGE, task?.topActivity?.packageName)
                }
                probe(preparedA, true); probe(preparedB, false)
                probe(preparedA, true); probe(preparedB, false)
                listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach {
                    assertTrue(operations.setClonePermission(preparedA.item(), it, false))
                }
                probe(preparedA, false); probe(preparedB, false)
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            // Every query is restricted to an exact recorded UUID and projects only owned row IDs.
            markers.forEach { marker ->
                try {
                    val ids: List<Long> = context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI,
                        arrayOf(ContactsContract.RawContacts._ID), "${ContactsContract.RawContacts.SOURCE_ID} = ?",
                        arrayOf(marker), null)?.use { cursor ->
                            val rows = mutableListOf<Long>()
                            while (cursor.moveToNext()) rows.add(cursor.getLong(0))
                            rows.toList()
                        }
                        ?: error("Owned contact cleanup inventory unavailable")
                    ids.forEach { id -> check(context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                        "${ContactsContract.RawContacts.SOURCE_ID} = ?", arrayOf(marker)) == 1) }
                    context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
                        "${ContactsContract.RawContacts.SOURCE_ID} = ?", arrayOf(marker), null)?.use { assertEquals("Owned marker cleanup", 0, it.count) }
                        ?: error("Owned cleanup verification unavailable")
                } catch (error: Throwable) { errors += error }
            }
            owned.asReversed().forEach { id -> try { operations.deleteGroup(id); assertNull(store.find(id)) } catch (error: Throwable) { errors += error } }
            try { assertEquals(baseline, tuples(store.loadSnapshot().groups)) } catch (error: Throwable) { errors += error }
            if (errors.isEmpty()) try { check(!journal.exists() || journal.delete()) } catch (error: Throwable) { errors += error }
            if (primary != null) errors.forEach(primary!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }
    private fun Group.user() = requireNotNull(environmentBinding).internalId
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE }, "Owned contacts fixture", "fixture", true, "")
    private fun tuples(groups: List<Group>) = groups.sortedBy { it.id }.map { "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",") }" }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
