package org.apptwin

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.lody.virtual.client.isolated.IsolatedWorkerProbe
import com.lody.virtual.client.stub.DaemonJobService
import org.apptwin.permissions.permissionSettingsDestination
import org.apptwin.runtime.DaemonWorkloadAuthorization
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.runtime.mainActivityLaunchHosts
import org.apptwin.ui.AppTwinApp

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private var packageReceiverRegistered = false
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val changedPackage = intent.data?.schemeSpecificPart ?: return
            if (changedPackage.isBlank() || action !in PACKAGE_CHANGE_ACTIONS) return
            mainViewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        setContent {
            AppTwinApp(
                viewModel = mainViewModel,
                onOpenStorageSettings = ::openAllFilesAccessSettings,
                onOpenPermissionSettings = ::openPermissionSettings,
                onShareDiagnostics = ::shareDiagnostics,
            )
        }
        if (BuildConfig.DEBUG) {
            if (intent.action == ACTION_PROBE_ISOLATED_WORKER) {
                IsolatedWorkerProbe.run(
                    this,
                    SHOPEE_PACKAGE,
                    SHOPEE_ISOLATED_SERVICE,
                    SHOPEE_NATIVE_LIBRARY,
                    intent.getBooleanExtra(EXTRA_LOAD_NATIVE, false),
                    intent.getBooleanExtra(EXTRA_CREATE_GUEST_SERVICE, false),
                )
            }
            val debugPackage = when (intent.action) {
                ACTION_LAUNCH_LINE_CLONE -> GroupAppRuntimeSupport.LINE_PACKAGE
                ACTION_LAUNCH_CLONE -> intent.getStringExtra(EXTRA_PACKAGE_NAME)
                else -> null
            }?.takeIf(String::isNotBlank)
            window.decorView.post {
                debugPackage?.let(mainViewModel::launchFirst)
            }
        }
        routeProductIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeProductIntent(intent)
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

    override fun onResume() {
        super.onResume()
        mainActivityLaunchHosts.onResumed(this)
        if (::mainViewModel.isInitialized) mainViewModel.refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        // A resumed activity can still be sleeping or waiting behind a system transition. Waiting
        // for window focus makes this a genuine user-visible FGS start on Android 12+.
        try {
            DaemonWorkloadAuthorization.startFromVisibleHost(this)
            // This job repairs an already-enabled workload; it never starts the foreground
            // service. Scheduling it here records an explicit, user-visible entry into AppTwin.
            DaemonJobService.scheduleJob(this)
        } catch (exception: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                exception is ForegroundServiceStartNotAllowedException
            ) {
                return
            }
            throw exception
        }
    }

    override fun onPause() {
        mainActivityLaunchHosts.onPaused(this)
        super.onPause()
    }

    override fun onStop() {
        if (packageReceiverRegistered) {
            unregisterReceiver(packageChangeReceiver)
            packageReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainActivityLaunchHosts.onPaused(this)
        super.onDestroy()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        openPermissionSettings(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    }

    private fun openPermissionSettings(permission: String) {
        val packageUri = Uri.parse("package:$packageName")
        val destination = permissionSettingsDestination(permission)
        val preferred = Intent(destination.action).apply {
            if (destination.packageScoped) data = packageUri
        }
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        runCatching { startActivity(preferred) }
            .recoverCatching { startActivity(appDetails) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
    }

    private fun shareDiagnostics(report: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "AppTwin 診斷報告")
            .putExtra(Intent.EXTRA_TEXT, report)
        startActivity(Intent.createChooser(send, "分享去識別化診斷報告"))
    }

    private fun routeProductIntent(intent: Intent) {
        when (intent.action) {
            GroupAppLaunchContract.ACTION_LAUNCH_GROUP_APP -> {
                val groupId = intent.getStringExtra(GroupAppLaunchContract.EXTRA_GROUP_ID).orEmpty()
                val packageName = intent.getStringExtra(
                    GroupAppLaunchContract.EXTRA_PACKAGE_NAME,
                ).orEmpty()
                window.decorView.post { mainViewModel.launchGroupApp(groupId, packageName) }
            }
            Intent.ACTION_VIEW -> intent.dataString
                ?.takeIf { intent.data?.scheme in setOf("http", "https") }
                ?.let { uri -> window.decorView.post { mainViewModel.openDeepLink(uri) } }
        }
    }

    private companion object {
        const val ACTION_LAUNCH_LINE_CLONE = "org.apptwin.action.LAUNCH_LINE_CLONE"
        const val ACTION_LAUNCH_CLONE = "org.apptwin.action.LAUNCH_CLONE"
        const val ACTION_PROBE_ISOLATED_WORKER = "org.apptwin.action.PROBE_ISOLATED_WORKER"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_LOAD_NATIVE = "loadNative"
        const val EXTRA_CREATE_GUEST_SERVICE = "createGuestService"
        const val SHOPEE_PACKAGE = "com.shopee.tw"
        const val SHOPEE_ISOLATED_SERVICE = "com.shopee.shpssdk.wvvvuvww"
        const val SHOPEE_NATIVE_LIBRARY = "libshpssdk.so"
        val PACKAGE_CHANGE_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
        )
    }
}
