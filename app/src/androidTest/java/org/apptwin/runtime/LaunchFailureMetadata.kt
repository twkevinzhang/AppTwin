package org.apptwin.runtime

import android.app.ActivityManager
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apptwin.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/** Failure-only diagnostics: no raw dumps, UI text, Intent data, account identifiers or payloads. */
@Suppress("DEPRECATION")
internal fun captureLaunchFailureMetadata(user: Int): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val result = JSONObject().put("virtualUser", user)
    fun attempt(key: String, action: () -> Any) {
        try { result.put(key, action()) }
        catch (error: Throwable) { result.put("${key}Failure", error.javaClass.simpleName) }
    }
    fun component(value: ComponentName?): String = when {
        value == null -> "NONE"
        value.packageName == "org.apptwin.fixture" && value.className.endsWith("FixtureActivity") -> value.flattenToShortString()
        value.packageName == context.packageName && (value.className == MainActivity::class.java.name ||
            value.className.startsWith("com.lody.virtual.client.stub.StubActivity")) -> value.flattenToShortString()
        else -> "OTHER"
    }
    attempt("fixtureLifecycle") {
        val raw = JSONObject(File(VEnvironment.getDataUserPackageDirectory(user, "org.apptwin.fixture"),
            "files/fixture-lifecycle.json").readText())
        JSONObject().apply {
            listOf("instance", "pid", "task", "launchCount", "savedInstance", "orientation", "density",
                "uiMode", "phase", "resumed", "focused", "changingConfigurations", "finishing")
                .forEach { key -> if (raw.has(key)) put(key, raw.get(key)) }
        }
    }
    attempt("tasks") {
        JSONArray().apply {
            context.getSystemService(ActivityManager::class.java).getRunningTasks(10).forEach { task ->
                val virtual = VActivityManager.get().getTaskInfo(task.id)
                val exactGuest = virtual?.userId == user && virtual.topActivity?.packageName == "org.apptwin.fixture"
                val host = task.topActivity?.className == MainActivity::class.java.name ||
                    task.baseActivity?.className == MainActivity::class.java.name
                if (exactGuest || host) put(JSONObject().put("task", task.id)
                    .put("top", component(task.topActivity)).put("base", component(task.baseActivity))
                    .put("exactGuest", exactGuest).put("virtualUser", virtual?.userId ?: -1))
            }
        }
    }
    attempt("appTasks") {
        JSONArray().apply {
            context.getSystemService(ActivityManager::class.java).appTasks.take(10).forEach { task ->
                val info = task.taskInfo
                val modern = android.os.Build.VERSION.SDK_INT >= 29
                val canonical = if (modern) info.taskId else info.persistentId
                val virtual = VActivityManager.get().getTaskInfo(canonical)
                val exactGuest = virtual?.userId == user && virtual.topActivity?.packageName == "org.apptwin.fixture"
                val host = info.topActivity?.className == MainActivity::class.java.name ||
                    info.baseActivity?.className == MainActivity::class.java.name
                if (exactGuest || host) {
                    val entry = JSONObject().put("canonicalTask", canonical).put("legacyId", info.id)
                        .put("persistentId", info.persistentId).put("top", component(info.topActivity))
                        .put("base", component(info.baseActivity)).put("exactGuest", exactGuest)
                        .put("virtualUser", virtual?.userId ?: -1)
                    if (modern) entry.put("isRunning", info.isRunning).put("numActivities", info.numActivities)
                    put(entry)
                }
            }
        }
    }
    attempt("host") {
        var host = JSONObject().put("registered", false)
        val complete = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                mainActivityLaunchHosts.current()?.let { activity ->
                    host = JSONObject().put("registered", true).put("task", activity.taskId)
                        .put("lifecycle", activity.lifecycle.currentState.name)
                        .put("focused", activity.hasWindowFocus()).put("finishing", activity.isFinishing)
                        .put("destroyed", activity.isDestroyed)
                }
            } finally { complete.countDown() }
        }
        check(complete.await(3, TimeUnit.SECONDS)) { "Host diagnostic timed out" }
        host
    }
    attempt("accessibilityRoot") {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val pkg = root?.packageName?.toString()
        JSONObject().put("package", if (pkg == context.packageName || pkg == "org.apptwin.fixture") pkg else "OTHER")
    }
    // The shell worker is bounded; only fixed component matches survive parsing.
    attempt("windowFocus") {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "owned-focus-diagnostic").apply { isDaemon = true } }
        var descriptor: android.os.ParcelFileDescriptor? = null
        val future = executor.submit<JSONObject> {
            descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys window")
            try { FileInputStream(requireNotNull(descriptor).fileDescriptor).bufferedReader().use { reader ->
                val safe = JSONObject()
                reader.forEachLine { line ->
                    val key = when {
                        line.contains("mCurrentFocus=") -> "currentFocus"
                        line.contains("mFocusedApp=") -> "focusedApp"
                        else -> null
                    }
                    if (key != null) {
                        val match = Regex("org\\.apptwin/(?:org\\.apptwin\\.MainActivity|\\.MainActivity|com\\.lody\\.virtual\\.client\\.stub\\.StubActivity\\$[A-Za-z0-9]+)").find(line)
                        safe.put(key, match?.value ?: "OTHER_OR_NONE")
                    }
                }
                safe
            } } finally { runCatching { descriptor?.close() } }
        }
        try { future.get(3, TimeUnit.SECONDS) }
        finally { future.cancel(true); runCatching { descriptor?.close() }; executor.shutdownNow() }
    }
    val text = result.toString()
    attempt("persisted") {
        File(context.cacheDir, "add-clone-failure-$user.json").writeText(text)
        true
    }
    return result.toString()
}
