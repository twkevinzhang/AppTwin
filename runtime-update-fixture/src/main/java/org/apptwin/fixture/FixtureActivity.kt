package org.apptwin.fixture

import android.app.Activity
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** A deliberately tiny guest whose files prove that a runtime code update preserves private data. */
class FixtureActivity : Activity() {
    private val lifecycleProbe by lazy { FixtureLifecycleProbe(this) }
    private var selectedUriProbe: SelectedUriProbe? = null
    private var permissionProbeStarted = false
    @Volatile private var probeResumed = false
    private var probeReadinessDeadline = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sentinel = File(filesDir, SENTINEL_FILE)
        if (!sentinel.exists()) {
            sentinel.writeText("created-by-revision=${BuildConfig.FIXTURE_REVISION}")
        }
        val countFile = File(filesDir, LAUNCH_COUNT_FILE)
        val launchCount = countFile.takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.toIntOrNull()
            ?.plus(1)
            ?: 1
        countFile.writeText(launchCount.toString())
        lifecycleProbe.created(savedInstanceState != null, launchCount)
        File(filesDir, PERMISSION_STATE_FILE).writeText(
            buildString {
                append("camera=")
                append(permissionGranted(Manifest.permission.CAMERA))
                append("\nmicrophone=")
                append(permissionGranted(Manifest.permission.RECORD_AUDIO))
            },
        )
        postFixtureNotification()

        val probeJson = EnvironmentProbe.collect(this).toJson()
        runCatching { File(filesDir, ENVIRONMENT_PROBE_FILE).writeText(probeJson) }
        Log.i(ENVIRONMENT_PROBE_LOG_TAG, probeJson)

        if (File(filesDir, SelectedUriProbe.ENABLE_FILE).exists()) {
            selectedUriProbe = SelectedUriProbe(this).also { it.show() }
            return
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    TextView(this@FixtureActivity).apply {
                        text = buildString {
                            append("AppTwin fixture revision ${BuildConfig.FIXTURE_REVISION}\n")
                            append("launch $launchCount\n\n")
                            append(probeJson)
                            append("\n\nadb logcat -s $ENVIRONMENT_PROBE_LOG_TAG:I")
                        }
                        textSize = 15f
                        setTextIsSelectable(true)
                        setPadding(48, 48, 48, 48)
                    },
                )
            },
        )
    }

    override fun onResume() {
        super.onResume()
        probeResumed = true
        lifecycleProbe.resumed()
        if (File(filesDir, "enable-permission-probe").exists() ||
            File(filesDir, CameraMicrophoneProbe.ENABLE_FILE).exists() ||
            File(filesDir, OwnedContactsProbe.ENABLE_FILE).exists()) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (probeReadinessDeadline == 0L) {
                probeReadinessDeadline = android.os.SystemClock.elapsedRealtime() + 20_000
            }
            window.decorView.post { startProbeWhenForeground() }
        }
    }

    override fun onPause() {
        probeResumed = false
        lifecycleProbe.paused()
        super.onPause()
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        lifecycleProbe.focus(hasFocus)
        if (hasFocus) window.decorView.post { startProbeWhenForeground() }
    }

    override fun onStop() {
        lifecycleProbe.stopped()
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleProbe.destroyed()
        super.onDestroy()
    }

    private fun startProbeWhenForeground() {
        val cameraRequest = File(filesDir, CameraMicrophoneProbe.ENABLE_FILE)
        val cameraOnly = cameraRequest.exists()
        val contactsRequest = File(filesDir, OwnedContactsProbe.ENABLE_FILE)
        val contactsOnly = contactsRequest.exists()
        val resultFile = when {
            contactsOnly -> OwnedContactsProbe.RESULT_FILE
            cameraOnly -> CameraMicrophoneProbe.RESULT_FILE
            else -> PermissionEnforcementProbe.RESULT_FILE
        }
        if (permissionProbeStarted || (!contactsOnly && !cameraOnly && !File(filesDir, "enable-permission-probe").exists())) return
        if (!probeResumed || !hasWindowFocus() || isFinishing) {
            if (probeReadinessDeadline == 0L) return
            if (android.os.SystemClock.elapsedRealtime() < probeReadinessDeadline) {
                window.decorView.postDelayed({ startProbeWhenForeground() }, 100)
            } else {
                permissionProbeStarted = true
                File(filesDir, resultFile).writeText(
                    org.json.JSONObject().put("readiness", "foreground-timeout")
                        .put("resumed", probeResumed).put("hasWindowFocus", hasWindowFocus())
                        .put("isFinishing", isFinishing).toString(),
                )
            }
            return
        }
        permissionProbeStarted = true
        Thread {
            val result = if (contactsOnly) OwnedContactsProbe.collect(this, org.json.JSONObject(contactsRequest.readText()))
            else if (cameraOnly) CameraMicrophoneProbe.collect(this, cameraRequest.readText().trim())
            else PermissionEnforcementProbe.collect(this) {
                org.json.JSONObject().put("resumed", probeResumed)
                    .put("hasWindowFocus", hasWindowFocus()).put("isFinishing", isFinishing)
                    .put("elapsedRealtime", android.os.SystemClock.elapsedRealtime())
            }
            File(filesDir, resultFile)
                .writeText(result.toString())
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        selectedUriProbe?.onResult(requestCode, resultCode, data)
    }

    private fun permissionGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun postFixtureNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "AppTwin fixture",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        manager.notify(
            NOTIFICATION_ID,
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("AppTwin fixture")
                .setContentText("notification routing probe")
                .build(),
        )
    }

    companion object {
        const val LAUNCH_COUNT_FILE = "launch-count.txt"
        const val SENTINEL_FILE = "revision-sentinel.txt"
        const val PERMISSION_STATE_FILE = "permission-state.txt"
        const val ENVIRONMENT_PROBE_FILE = "environment-probe.json"
        const val ENVIRONMENT_PROBE_LOG_TAG = "AppTwinEnvProbe"
        const val NOTIFICATION_CHANNEL = "fixture"
        const val NOTIFICATION_ID = 7
    }
}
