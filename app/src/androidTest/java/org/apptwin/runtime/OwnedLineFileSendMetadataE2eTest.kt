package org.apptwin.runtime

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.os.VEnvironment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in schema or exact owned-file metadata inspection; never infers delivery from unknown status. */
@RunWith(AndroidJUnit4::class)
class OwnedLineFileSendMetadataE2eTest {
    @Test fun inspectOwnedSendMetadataReadOnly() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("ownedLineFileSendMetadataE2e") == "true")
        val name = requireNotNull(arguments.getString("ownedLineFileName"))
        require(name == "apptwin-file-probe-20261013.txt")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("org.apptwin", context.packageName)
        val databaseFile = File(VEnvironment.getDataUserPackageDirectory(USER, PACKAGE), "databases/naver_line")
        assertTrue("Exact LINE user 2 database must already exist", databaseFile.isFile)
        val samples = JSONArray()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertTrue("Read-only database connection", database.isReadOnly)
            database.rawQuery(
                "SELECT status, type, created_time, delivered_time, sent_count, read_count, " +
                    "CASE WHEN server_id IS NOT NULL AND LENGTH(CAST(server_id AS TEXT)) > 0 " +
                    "AND CAST(server_id AS TEXT) != '0' THEN 1 ELSE 0 END AS server_id_present " +
                    "FROM chat_history WHERE content = ? OR parameter LIKE ? " +
                    "ORDER BY created_time DESC LIMIT 10",
                arrayOf(name, "%$name%")
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    check(samples.length() < 10)
                    val sample = JSONObject()
                    for (column in listOf("status", "type", "created_time", "delivered_time", "sent_count", "read_count")) {
                        val index = cursor.getColumnIndexOrThrow(column)
                        // Unexpected strings are never emitted, even when the owned row matched.
                        val value: Any = when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            else -> "nonNumeric"
                        }
                        sample.put(column, value)
                    }
                    sample.put("serverIdPresent", cursor.getInt(cursor.getColumnIndexOrThrow("server_id_present")) == 1)
                    samples.put(sample)
                }
            }
        }
        val metadata = JSONObject().put("readOnly", true).put("ownedRowsOnly", true)
            .put("count", samples.length()).put("sampleLimit", 10).put("samples", samples)
        File(context.cacheDir, "owned-line-file-send-metadata.json").writeText(metadata.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("ownedLineFileSendMetadata", metadata.toString()) })
    }

    @Test fun inspectOwnedSendSchemaReadOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedLineFileSchemaE2e") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("org.apptwin", context.packageName)
        val databaseFile = File(VEnvironment.getDataUserPackageDirectory(USER, PACKAGE), "databases/naver_line")
        assertTrue("Exact LINE user 2 database must already exist", databaseFile.isFile)
        val columns = JSONArray()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertTrue("Read-only database connection", database.isReadOnly)
            database.rawQuery("PRAGMA table_info(chat_history)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    check(columns.length() < 128) { "Unexpected schema size" }
                    columns.put(cursor.getString(nameIndex))
                }
            }
        }
        assertTrue("chat_history schema must exist", columns.length() > 0)
        val metadata = JSONObject().put("readOnly", true).put("schemaOnly", true).put("columns", columns)
        File(context.cacheDir, "owned-line-file-send-metadata.json").writeText(metadata.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("ownedLineFileSchema", metadata.toString()) })
    }

    private companion object {
        const val USER = 2
        const val PACKAGE = "jp.naver.line.android"
    }
}
