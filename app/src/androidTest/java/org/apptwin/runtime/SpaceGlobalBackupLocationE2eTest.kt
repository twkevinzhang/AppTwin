package org.apptwin.runtime

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.archive.SpaceArchiveCompression
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device SAF acceptance. Both dedicated folders must be granted through system UI. */
@RunWith(AndroidJUnit4::class)
class SpaceGlobalBackupLocationE2eTest {
    @Test fun globalLocationChangesOnlyNewBackupsAndPermanentDeletionRemovesFiles() = runBlocking {
        assumeTrue("Requires opt-in and pre-granted dedicated SAF trees",
            InstrumentationRegistry.getArguments().getString("spaceGlobalBackupE2e") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val trees = listOf("primary:AppTwin-Acceptance", "primary:AppTwin-Acceptance-Next")
            .map { DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", it) }
        trees.forEach { tree ->
            assertTrue("Dedicated folder requires persisted read/write grant: $tree",
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == tree && it.isReadPermission && it.isWritePermission
                })
        }
        val originalGlobal = requireNotNull(operations.globalBackupLocation()) {
            "Configure an existing Global location before running this acceptance test"
        }
        assertTrue("Original Global location must remain restorable",
            context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == originalGlobal && it.isReadPermission && it.isWritePermission
            })
        val testName = "apptwin-global-acceptance-${System.nanoTime()}"
        var ownedGroup: String? = null
        val ownedEntryIds = mutableSetOf<String>()
        var failure: Throwable? = null
        try {
            val group = operations.createGroup(testName)
            ownedGroup = group.id
            operations.setGlobalBackupLocation(trees[0])
            operations.exportToGlobalLocation(group.id, SpaceArchiveCompression.LOW)
            val first = requireNotNull(operations.loadSpaceDetails(group.id)).backups.single()
            ownedEntryIds += first.id
            assertTrue(first.bytes > 0 && first.createdAt > 0 && first.accessible)
            assertEquals(DocumentsContract.getTreeDocumentId(trees[0]),
                DocumentsContract.getTreeDocumentId(Uri.parse(first.uri)))

            operations.setGlobalBackupLocation(trees[1])
            operations.exportToGlobalLocation(group.id, SpaceArchiveCompression.LOW)
            val entries = requireNotNull(operations.loadSpaceDetails(group.id)).backups
            ownedEntryIds += entries.map { it.id }
            assertEquals(2, entries.size)
            val old = entries.single { it.id == first.id }
            val next = entries.single { it.id != first.id }
            assertEquals("Changing Global must not move old backups", first.uri, old.uri)
            assertNotEquals(first.uri, next.uri)
            assertEquals(DocumentsContract.getTreeDocumentId(trees[1]),
                DocumentsContract.getTreeDocumentId(Uri.parse(next.uri)))
            entries.forEach { entry ->
                assertTrue(entry.bytes > 0 && entry.createdAt > 0 && entry.accessible)
                assertTrue("Physical SAF document must exist", containsDocument(context, Uri.parse(entry.uri)))
                operations.deleteBackup(entry.id)
                ownedEntryIds -= entry.id
                assertFalse("Permanent delete must remove actual document", containsDocument(context, Uri.parse(entry.uri)))
                assertFalse(requireNotNull(operations.loadSpaceDetails(group.id)).backups.any { it.id == entry.id })
            }
            assertTrue(requireNotNull(operations.loadSpaceDetails(group.id)).backups.isEmpty())
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) { cleanupFailures += error }
            }
            // Discover every registered export, including one created before an assertion failed.
            ownedGroup?.let { id -> attempt {
                ownedEntryIds += requireNotNull(operations.loadSpaceDetails(id)).backups.map { it.id }
            } }
            ownedEntryIds.toList().forEach { id -> attempt { operations.deleteBackup(id) } }
            // Remove only this run's named documents if export failed before registry persistence.
            trees.forEach { tree -> attempt {
                ownDocuments(context, tree, testName).forEach { uri ->
                    try { check(DocumentsContract.deleteDocument(context.contentResolver, uri)) }
                    catch (error: Throwable) { cleanupFailures += error }
                }
            } }
            ownedGroup?.let { id -> attempt { operations.deleteGroup(id) } }
            attempt { operations.setGlobalBackupLocation(Uri.parse(originalGlobal)) }
            if (failure != null) cleanupFailures.forEach { failure!!.addSuppressed(it) }
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun containsDocument(context: Context, document: Uri): Boolean {
        val tree = DocumentsContract.buildTreeDocumentUri(document.authority,
            DocumentsContract.getTreeDocumentId(document))
        val id = DocumentsContract.getDocumentId(document)
        return queryChildren(context, tree).any { it.first == id }
    }

    private fun ownDocuments(context: Context, tree: Uri, prefix: String): List<Uri> =
        queryChildren(context, tree).filter { it.second.startsWith("$prefix-") && it.second.endsWith(".apptwin-space") }
            .map { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }

    private fun queryChildren(context: Context, tree: Uri): List<Pair<String, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = requireNotNull(context.contentResolver.query(children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)) { "Cannot verify dedicated SAF folder" }
        return cursor.use {
            buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) }
        }
    }
}
