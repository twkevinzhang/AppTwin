package org.apptwin.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri

class FixtureSyncProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        args: Array<out String>?, order: String?) = MatrixCursor(arrayOf("completed")).apply {
        addRow(arrayOf(context!!.getSharedPreferences("fixture_sync_proof", 0).getInt("completed", 0)))
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.apptwin.sync-proof"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
