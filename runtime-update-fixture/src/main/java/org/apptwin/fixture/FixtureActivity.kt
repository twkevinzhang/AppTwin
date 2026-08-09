package org.apptwin.fixture

import android.app.Activity
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** A deliberately tiny guest whose files prove that a runtime code update preserves private data. */
class FixtureActivity : Activity() {
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
        File(filesDir, PERMISSION_STATE_FILE).writeText(
            buildString {
                append("camera=")
                append(permissionGranted(Manifest.permission.CAMERA))
                append("\nmicrophone=")
                append(permissionGranted(Manifest.permission.RECORD_AUDIO))
            },
        )
        postFixtureNotification()

        setContentView(
            TextView(this).apply {
                text = "AppTwin fixture revision ${BuildConfig.FIXTURE_REVISION}\nlaunch $launchCount"
                textSize = 22f
                setPadding(48, 48, 48, 48)
            },
        )
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
        const val NOTIFICATION_CHANNEL = "fixture"
        const val NOTIFICATION_ID = 7
    }
}
