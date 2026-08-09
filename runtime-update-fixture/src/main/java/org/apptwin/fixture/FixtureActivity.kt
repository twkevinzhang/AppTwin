package org.apptwin.fixture

import android.app.Activity
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

        setContentView(
            TextView(this).apply {
                text = "AppTwin fixture revision ${BuildConfig.FIXTURE_REVISION}\nlaunch $launchCount"
                textSize = 22f
                setPadding(48, 48, 48, 48)
            },
        )
    }

    companion object {
        const val LAUNCH_COUNT_FILE = "launch-count.txt"
        const val SENTINEL_FILE = "revision-sentinel.txt"
    }
}
