package org.apptwin.fixture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject

/** Explicit test-owned opt-in; buffers are discarded and only capability outcomes are written. */
object CameraMicrophoneProbe {
    const val ENABLE_FILE = "enable-camera-mic-probe"
    const val RESULT_FILE = "camera-mic-outcomes.json"

    fun collect(context: Context, requestId: String): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("cameraOpen", outcome {
            @Suppress("DEPRECATION")
            val camera = android.hardware.Camera.open()
            try { true } finally { camera.release() }
        })
        put("microphoneRead", outcome {
            val minimum = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val recorder = try { AudioRecord(MediaRecorder.AudioSource.MIC, 8000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 2048)) } catch (denied: SecurityException) { throw denied }
            try {
                recorder.startRecording()
                val deadline = SystemClock.elapsedRealtime() + 1500
                var count = 0
                val discarded = ByteArray(256)
                while (count == 0 && SystemClock.elapsedRealtime() < deadline) {
                    count = recorder.read(discarded, 0, discarded.size, AudioRecord.READ_NON_BLOCKING)
                    if (count == 0) Thread.sleep(25)
                }
                discarded.fill(0)
                count > 0
            } finally { runCatching { recorder.stop() }; recorder.release() }
        })
    }

    private fun outcome(operation: () -> Boolean): String = try {
        if (operation()) "allowed" else "empty"
    } catch (failure: Throwable) { "blocked:${failure.javaClass.simpleName}" }
}
