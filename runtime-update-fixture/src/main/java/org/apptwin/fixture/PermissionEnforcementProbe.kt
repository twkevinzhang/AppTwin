package org.apptwin.fixture

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/** Opt-in real API probe. Only removes the contact that this invocation created. */
object PermissionEnforcementProbe {
    const val RESULT_FILE = "permission-enforcement.json"
    fun collect(context: Context, state: (() -> JSONObject)? = null): JSONObject {
        val result = JSONObject()
        fun probe(name: String, block: () -> Boolean) {
            state?.let { result.put("${name}State", it()) }
            try { result.put(name, if (block()) "allowed" else "empty") }
            catch (failure: Throwable) {
                result.put(name, "blocked:${failure.javaClass.simpleName}")
                if (name.contains("Location", ignoreCase = true)) {
                    val message = failure.message.orEmpty().lowercase()
                    val reason = when {
                        "background" in message -> "background-denied"
                        "space location" in message -> "space-location-denied"
                        "permission" in message || "access_fine_location" in message || "access_coarse_location" in message -> "system-permission-denied"
                        else -> "unclassified"
                    }
                    result.put("${name}Denial", reason)
                }
            }
        }
        val resolver = context.contentResolver
        probe("contactsRead") {
            resolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id"), null, null, null)
                ?.use { it.count > 0 } ?: false
        }
        probe("contactsWrite") {
            val uri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, "org.apptwin.fixture")
                put(ContactsContract.RawContacts.ACCOUNT_NAME, "permission-probe")
            }) ?: return@probe false
            check(resolver.delete(uri, null, null) == 1) { "Probe contact cleanup failed" }
            true
        }
        probe("photosRead") {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf("apptwin-permission-sentinel.png"), null)
                ?.use { it.moveToFirst() } ?: false
        }
        probe("photosOpen") {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf("apptwin-permission-sentinel.png"), null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) false else {
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                        resolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
                    }
                } ?: false
        }
        probe("photosDirect") {
            File("/sdcard/Pictures/apptwin-permission-sentinel.png").inputStream().use { it.read() >= 0 }
        }
        probe("locationSubscription") {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = LocationListener { }
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                true
            } catch (denied: SecurityException) { throw denied }
            finally { manager.removeUpdates(listener) }
        }
        probe("coarseLocationSubscription") {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = LocationListener { }
            try {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                true
            } catch (denied: SecurityException) { throw denied }
            finally { manager.removeUpdates(listener) }
        }
        probe("locationRead") {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) != null }
            catch (denied: SecurityException) { throw denied }
        }
        probe("cameraOpen") {
            @Suppress("DEPRECATION")
            val camera = android.hardware.Camera.open()
            camera.release()
            true
        }
        probe("microphoneRead") {
            val minimum = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = try { AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 2048)) } catch (denied: SecurityException) { throw denied }
            try {
                recorder.startRecording()
                val deadline = android.os.SystemClock.elapsedRealtime() + 1500
                var count = 0
                while (count == 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                    count = recorder.read(ByteArray(256), 0, 256, AudioRecord.READ_NON_BLOCKING)
                    if (count == 0) Thread.sleep(25)
                }
                count > 0
            } finally { runCatching { recorder.stop() }; recorder.release() }
        }
        return result
    }
}
