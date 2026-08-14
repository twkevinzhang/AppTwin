package org.apptwin.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.env.VirtualRuntime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Debug-only bridge between AppTwin's virtual processes and Firebase Crashlytics. */
object CrashReporting {
    private val initialized = AtomicBoolean(false)

    fun initialize(application: Application, virtualCore: VirtualCore) {
        if (!initialized.compareAndSet(false, true)) return

        val processRole = processRole(application, virtualCore)
        if (processRole == PROCESS_ROLE_VIRTUAL) {
            installGuestCrashHandler(application, virtualCore)
            return
        }

        val firebaseApp = runCatching {
            FirebaseApp.getApps(application).firstOrNull()
                ?: FirebaseApp.initializeApp(application)
        }.getOrElse {
            Log.e(TAG, "Firebase initialization failed")
            null
        }
        if (firebaseApp == null) {
            Log.e(TAG, "Firebase configuration is unavailable; crash reporting is disabled")
            return
        }

        val crashlytics = runCatching { FirebaseCrashlytics.getInstance() }
            .getOrElse {
                Log.e(TAG, "Crashlytics initialization failed")
                return
            }
        crashlytics.setCrashlyticsCollectionEnabled(true)

        setBaseKeys(crashlytics, processRole)

        if (processRole == PROCESS_ROLE_MAIN) {
            thread(name = "apptwin-crash-replay", isDaemon = true) {
                replayGuestCrashes(application, crashlytics)
                reportHistoricalProcessExits(application, crashlytics)
                setBaseKeys(crashlytics, PROCESS_ROLE_MAIN)
            }
        }
    }

    private fun installGuestCrashHandler(
        context: Context,
        virtualCore: VirtualCore,
    ) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val journal = GuestCrashJournal(context)
        virtualCore.setCrashHandler { crashingThread, throwable ->
            val record = runCatching {
                val packageName = VirtualRuntime.getInitialPackageName()
                    ?.takeIf(String::isNotBlank)
                    ?: UNKNOWN_IDENTITY
                val processName = VirtualRuntime.getProcessName()
                    ?.takeIf(String::isNotBlank)
                    ?: UNKNOWN_IDENTITY
                journal.append(packageName, processName, throwable)
            }.getOrElse {
                Log.e(TAG, "Unable to persist virtual guest crash")
                null
            }
            // The virtual runtime replaces the root ThreadGroup handler. Handing the sanitized
            // exception back to Android's original handler preserves fatal semantics without
            // initializing Firebase or scheduling host jobs under the virtual guest identity.
            if (previousHandler != null) {
                previousHandler.uncaughtException(
                    crashingThread,
                    record?.reconstructThrowable() ?: throwable.withoutMessage(),
                )
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(GUEST_CRASH_EXIT_CODE)
            }
        }
    }

    private fun replayGuestCrashes(context: Context, crashlytics: FirebaseCrashlytics) {
        val journal = GuestCrashJournal(context)
        journal.pending().forEach { record ->
            runCatching {
                crashlytics.setCustomKey(KEY_EVENT_TYPE, EVENT_GUEST_FATAL_REPLAY)
                crashlytics.setCustomKey(KEY_PROCESS_ROLE, PROCESS_ROLE_VIRTUAL)
                crashlytics.setCustomKey(KEY_GUEST_PACKAGE_HASH, record.packageNameHash)
                crashlytics.setCustomKey(KEY_GUEST_PROCESS_HASH, record.processNameHash)
                crashlytics.setCustomKey(KEY_GUEST_EXCEPTION_CLASS, record.exceptionClassName)
                crashlytics.recordException(record.reconstructThrowable())
                // recordException synchronously queues the report in Crashlytics' local store;
                // its SDK owns network retry from this point onward.
                journal.acknowledge(record.id)
            }.onFailure {
                Log.e(TAG, "Unable to queue persisted virtual guest crash")
            }
        }
        clearGuestKeys(crashlytics)
    }

    private fun reportHistoricalProcessExits(
        context: Context,
        crashlytics: FirebaseCrashlytics,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val preferences = context.getSharedPreferences(EXIT_STATE_PREFERENCES, Context.MODE_PRIVATE)
        val previousCursor = preferences.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_QUERY_LIMIT)
        }.getOrElse {
            Log.e(TAG, "Unable to query historical process exits")
            return
        }
        val newExits = exits
            .asSequence()
            .filter { it.timestamp > previousCursor }
            .sortedBy(ApplicationExitInfo::getTimestamp)
            .toList()
        if (newExits.isEmpty()) return

        newExits.forEach { exit ->
            if (exit.reason in REPORTABLE_EXIT_REASONS) {
                crashlytics.setCustomKey(KEY_EVENT_TYPE, EVENT_PREVIOUS_PROCESS_EXIT)
                crashlytics.setCustomKey(KEY_PROCESS_ROLE, processRole(exit.processName, context.packageName))
                crashlytics.setCustomKey(KEY_EXIT_REASON, exit.reason)
                crashlytics.setCustomKey(KEY_EXIT_IMPORTANCE, exit.importance)
                crashlytics.recordException(PreviousProcessExitException())
            }
        }
        preferences.edit()
            .putLong(KEY_LAST_EXIT_TIMESTAMP, newExits.maxOf(ApplicationExitInfo::getTimestamp))
            .apply()
        clearExitKeys(crashlytics)
    }

    private fun Throwable.withoutMessage(): Throwable = SanitizedGuestCrashException().also {
        it.stackTrace = stackTrace
    }

    private fun processRole(context: Context, virtualCore: VirtualCore): String {
        if (runCatching { virtualCore.isVAppProcess }.getOrDefault(false)) {
            return PROCESS_ROLE_VIRTUAL
        }
        return processRole(currentProcessName(context), context.packageName)
    }

    private fun processRole(processName: String?, packageName: String): String = when {
        processName == packageName -> PROCESS_ROLE_MAIN
        processName?.startsWith("$packageName:") == true -> PROCESS_ROLE_HOST_SECONDARY
        else -> PROCESS_ROLE_UNKNOWN
    }

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
    }

    private fun setBaseKeys(crashlytics: FirebaseCrashlytics, processRole: String) {
        crashlytics.setCustomKey(KEY_EVENT_TYPE, EVENT_HOST_RUNTIME)
        crashlytics.setCustomKey(KEY_PROCESS_ROLE, processRole)
        clearGuestKeys(crashlytics)
        clearExitKeys(crashlytics)
    }

    private fun clearGuestKeys(crashlytics: FirebaseCrashlytics) {
        crashlytics.setCustomKey(KEY_GUEST_PACKAGE_HASH, NOT_APPLICABLE)
        crashlytics.setCustomKey(KEY_GUEST_PROCESS_HASH, NOT_APPLICABLE)
        crashlytics.setCustomKey(KEY_GUEST_EXCEPTION_CLASS, NOT_APPLICABLE)
    }

    private fun clearExitKeys(crashlytics: FirebaseCrashlytics) {
        crashlytics.setCustomKey(KEY_EXIT_REASON, NOT_APPLICABLE_NUMBER)
        crashlytics.setCustomKey(KEY_EXIT_IMPORTANCE, NOT_APPLICABLE_NUMBER)
    }

    private class SanitizedGuestCrashException : RuntimeException()
    private class PreviousProcessExitException : RuntimeException()

    private const val TAG = "AppTwinCrash"
    private const val UNKNOWN_IDENTITY = "unknown"
    private const val NOT_APPLICABLE = "n/a"
    private const val NOT_APPLICABLE_NUMBER = -1
    private const val GUEST_CRASH_EXIT_CODE = 10
    private const val EXIT_QUERY_LIMIT = 20
    private const val EXIT_STATE_PREFERENCES = "crash_reporting_state"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"

    private const val KEY_EVENT_TYPE = "apptwin_event_type"
    private const val KEY_PROCESS_ROLE = "apptwin_process_role"
    private const val KEY_GUEST_PACKAGE_HASH = "apptwin_guest_package_hash"
    private const val KEY_GUEST_PROCESS_HASH = "apptwin_guest_process_hash"
    private const val KEY_GUEST_EXCEPTION_CLASS = "apptwin_guest_exception_class"
    private const val KEY_EXIT_REASON = "apptwin_exit_reason"
    private const val KEY_EXIT_IMPORTANCE = "apptwin_exit_importance"

    private const val EVENT_HOST_RUNTIME = "host_runtime"
    private const val EVENT_GUEST_FATAL_REPLAY = "guest_fatal_replay"
    private const val EVENT_PREVIOUS_PROCESS_EXIT = "previous_process_exit"
    private const val PROCESS_ROLE_MAIN = "main"
    private const val PROCESS_ROLE_VIRTUAL = "virtual"
    private const val PROCESS_ROLE_HOST_SECONDARY = "host_secondary"
    private const val PROCESS_ROLE_UNKNOWN = "unknown"

    private val REPORTABLE_EXIT_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )
}
