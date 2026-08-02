package org.maskaccounts

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors
import org.maskaccounts.revision.AndroidPackageRevisionImporter
import org.maskaccounts.revision.InstalledAppEntry
import org.maskaccounts.revision.RevisionImportResult
import org.maskaccounts.instances.FileInstanceStore
import org.maskaccounts.instances.VirtualInstance
import org.maskaccounts.runtime.RuntimeLaunchResult
import org.maskaccounts.runtime.VirtualRuntimeController

/**
 * Minimal M0 package-source picker.
 *
 * This screen deliberately does not launch or virtualize selected apps. It makes the package
 * visibility and storage preconditions observable while the runtime is developed separately.
 */
class MainActivity : Activity() {
    private lateinit var storageStatus: TextView
    private lateinit var appCount: TextView
    private lateinit var appList: ListView
    private lateinit var syncStatus: TextView
    private lateinit var importer: AndroidPackageRevisionImporter
    private lateinit var instanceStore: FileInstanceStore
    private lateinit var runtimeController: VirtualRuntimeController
    private val importExecutor = Executors.newSingleThreadExecutor()
    private var entries: List<InstalledAppEntry> = emptyList()
    private var packageReceiverRegistered = false
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val changedPackage = intent.data?.schemeSpecificPart ?: return
            if (changedPackage.isBlank() || action !in PACKAGE_CHANGE_ACTIONS) return
            refreshInstalledApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importer = AndroidPackageRevisionImporter(this)
        instanceStore = FileInstanceStore(this)
        runtimeController = VirtualRuntimeController(this)
        setContentView(createContentView())
        if (BuildConfig.DEBUG && intent.action == ACTION_LAUNCH_LINE_CLONE) {
            window.decorView.post {
                instanceStore.list(LINE_PACKAGE).firstOrNull()?.let(::launchInstance)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorageStatus()
        refreshInstalledApps()
    }

    override fun onStart() {
        super.onStart()
        if (packageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageChangeReceiver, filter)
        }
        packageReceiverRegistered = true
    }

    override fun onStop() {
        if (packageReceiverRegistered) {
            unregisterReceiver(packageChangeReceiver)
            packageReceiverRegistered = false
        }
        super.onStop()
    }

    private fun createContentView(): View {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.m0_title)
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.m0_subtitle)
            textSize = 16f
            setPadding(0, padding / 2, 0, padding)
        })

        storageStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, padding / 2, 0, padding)
            setOnClickListener { openAllFilesAccessSettings() }
        }
        root.addView(storageStatus)

        appCount = TextView(this).apply { textSize = 18f }
        root.addView(appCount)

        syncStatus = TextView(this).apply {
            text = getString(R.string.tap_to_sync)
            textSize = 15f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        root.addView(syncStatus)

        appList = ListView(this)
        appList.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let(::handlePackageClick)
        }
        root.addView(
            appList,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    private fun refreshStorageStatus() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        storageStatus.text = if (granted) {
            val downloadCount = publicDirectoryEntryCount(Environment.DIRECTORY_DOWNLOADS)
            val photoCount = publicDirectoryEntryCount(Environment.DIRECTORY_DCIM) +
                publicDirectoryEntryCount(Environment.DIRECTORY_PICTURES)
            getString(R.string.all_files_granted, downloadCount, photoCount)
        } else {
            getString(R.string.all_files_missing)
        }
        storageStatus.isEnabled = !granted
    }

    private fun publicDirectoryEntryCount(directoryType: String): Int = runCatching {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(directoryType).listFiles()?.size ?: 0
    }.getOrDefault(0)

    private fun refreshInstalledApps() {
        entries = importer.listCloneableApps()
        val labels = entries.map { entry ->
            val active = importer.active(entry.packageName)
            buildString {
                append(entry.label)
                append("  ")
                append(entry.versionName.ifBlank { entry.versionCode })
                append('\n')
                append(entry.packageName)
                if (active?.versionCode == entry.versionCode) {
                    append("  • 已同步 v${active.versionCode}")
                } else if (active != null) {
                    append("  • 需要同步 v${active.versionCode} → v${entry.versionCode}")
                }
                val instanceCount = instanceStore.list(entry.packageName).size
                if (instanceCount > 0) append("  • $instanceCount 個實例")
            }
        }

        appCount.text = getString(R.string.installed_apps, entries.size)
        appList.emptyView = TextView(this).apply { text = getString(R.string.no_apps) }
        appList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun syncPackage(entry: InstalledAppEntry) {
        appList.isEnabled = false
        syncStatus.text = getString(R.string.syncing_app, entry.label)
        importExecutor.execute {
            val result = importer.sync(entry.packageName)
            runOnUiThread {
                appList.isEnabled = true
                syncStatus.text = when (result) {
                    is RevisionImportResult.Activated -> getString(
                        R.string.sync_complete,
                        entry.label,
                        result.artifactCount,
                        result.bytesCopied / (1024 * 1024),
                    )
                    is RevisionImportResult.AlreadyCurrent ->
                        getString(R.string.sync_already_current, entry.label)
                    is RevisionImportResult.Rejected ->
                        getString(R.string.sync_rejected, result.reason)
                    is RevisionImportResult.Failed ->
                        getString(R.string.sync_failed, result.reason)
                }
                refreshInstalledApps()
            }
        }
    }

    private fun handlePackageClick(entry: InstalledAppEntry) {
        val active = importer.active(entry.packageName)
        if (active?.versionCode != entry.versionCode) {
            syncPackage(entry)
            return
        }
        val instances = instanceStore.list(entry.packageName)
        if (entry.packageName == LINE_PACKAGE) {
            showLineActions(entry, active.versionCode, instances)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setMessage(getString(R.string.package_actions_message, active.versionCode))
            .setPositiveButton(R.string.create_instance) { _, _ -> createInstance(entry) }
            .setNeutralButton(R.string.repair_revision) { _, _ -> syncPackage(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLineActions(
        entry: InstalledAppEntry,
        versionCode: Long,
        instances: List<VirtualInstance>,
    ) {
        val first = instances.firstOrNull()
        val message = if (first == null) {
            getString(R.string.line_runtime_ready, versionCode)
        } else {
            getString(R.string.line_runtime_instance_ready, versionCode, first.displayName)
        }
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setMessage(message)
            .setPositiveButton(
                if (first == null) R.string.create_and_launch else R.string.launch_clone,
            ) { _, _ ->
                if (first == null) createInstance(entry, launchAfterCreate = true) else launchInstance(first)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createInstance(entry: InstalledAppEntry, launchAfterCreate: Boolean = false) {
        val ordinal = instanceStore.list(entry.packageName).size + 1
        val instance = runCatching {
            instanceStore.create(
                packageName = entry.packageName,
                displayName = "${entry.label} $ordinal",
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        }.getOrElse { error ->
            syncStatus.text = getString(R.string.instance_failed, error.message ?: error.javaClass.simpleName)
            return
        }
        syncStatus.text = getString(R.string.instance_created, instance.displayName)
        refreshInstalledApps()
        if (launchAfterCreate) launchInstance(instance)
    }

    private fun launchInstance(instance: VirtualInstance) {
        appList.isEnabled = false
        syncStatus.text = getString(R.string.launching_clone, instance.displayName)
        importExecutor.execute {
            val result = runtimeController.installAndLaunch(instance)
            runOnUiThread {
                appList.isEnabled = true
                syncStatus.text = when (result) {
                    is RuntimeLaunchResult.Started -> getString(
                        R.string.clone_started,
                        instance.displayName,
                        result.processPrefix,
                        result.virtualUserId,
                    )
                    is RuntimeLaunchResult.Failed -> getString(
                        R.string.clone_launch_failed,
                        result.reason,
                    )
                }
            }
        }
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val packageUri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    override fun onDestroy() {
        importExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val ACTION_LAUNCH_LINE_CLONE = "org.maskaccounts.action.LAUNCH_LINE_CLONE"
        const val LINE_PACKAGE = "jp.naver.line.android"
        val PACKAGE_CHANGE_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
        )
    }
}
