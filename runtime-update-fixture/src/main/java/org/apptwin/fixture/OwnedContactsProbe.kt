package org.apptwin.fixture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONObject
import java.util.UUID

/** Only accesses explicitly owned local raw rows; never queries original contact contents. */
object OwnedContactsProbe {
    const val ENABLE_FILE = "enable-owned-contacts-probe"
    const val RESULT_FILE = "owned-contacts-outcomes.json"
    fun collect(context: Context, request: JSONObject): JSONObject {
        val requestId = request.getString("requestId").also { UUID.fromString(it) }
        val seedMarker = request.getString("seedMarker").also { UUID.fromString(it) }
        val seedId = request.getLong("seedId").also { require(it > 0) }
        val resolver = context.contentResolver
        val result = JSONObject().put("requestId", requestId)
        result.put("contactsRead", try {
            resolver.query(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, seedId),
                arrayOf(ContactsContract.RawContacts._ID), "${ContactsContract.RawContacts.SOURCE_ID} = ?",
                arrayOf(seedMarker), null)?.use { if (it.count == 1) "allowed" else "empty" } ?: "empty"
        } catch (denied: SecurityException) { "blocked:SecurityException" }
        catch (failure: Exception) { "blocked:${failure.javaClass.simpleName}" })
        var created: Uri? = null
        var writeFailure: Throwable? = null
        var writeOutcome = "empty"
        try {
            created = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
                putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                put(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED)
                put(ContactsContract.RawContacts.SOURCE_ID, requestId)
            })
            check(created != null) { "Owned insert failed" }
            val local = resolver.query(requireNotNull(created), arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE), null, null, null)?.use {
                it.moveToFirst() && it.isNull(0) && it.isNull(1)
            } ?: false
            check(local) { "Owned row is not local" }
            val ownId = ContentUris.parseId(requireNotNull(created))
            val emptyData = resolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(ownId.toString()), null)?.use { it.count == 0 } ?: false
            check(emptyData) { "Owned raw row unexpectedly contains data" }
            writeOutcome = "allowed"
        } catch (denied: SecurityException) {
            writeFailure = denied; writeOutcome = "blocked:SecurityException"
        } catch (failure: Exception) {
            writeFailure = failure; writeOutcome = "blocked:${failure.javaClass.simpleName}"
        } finally {
            if (created != null) {
                try {
                    check(resolver.delete(requireNotNull(created), null, null) == 1) { "Owned row cleanup incomplete" }
                    result.put("writeCleanup", true)
                } catch (cleanup: Exception) {
                    writeFailure?.addSuppressed(cleanup)
                    writeOutcome = "cleanup:${cleanup.javaClass.simpleName}"
                    result.put("writeCleanup", false)
                }
            }
        }
        return result.put("contactsWrite", writeOutcome)
    }
}
