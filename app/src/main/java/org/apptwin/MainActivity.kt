package org.apptwin

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
import androidx.lifecycle.ViewModelProvider
import com.lody.virtual.client.isolated.IsolatedWorkerProbe
import org.apptwin.runtime.GroupAppRuntimeSupport
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
            }?.takeIf(GroupAppRuntimeSupport::canLaunch)
            window.decorView.post {
                debugPackage?.let(mainViewModel::launchFirst)
            }
        }
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
        if (::mainViewModel.isInitialized) mainViewModel.refresh()
    }

    override fun onStop() {
        if (packageReceiverRegistered) {
            unregisterReceiver(packageChangeReceiver)
            packageReceiverRegistered = false
        }
        super.onStop()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val packageUri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
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
