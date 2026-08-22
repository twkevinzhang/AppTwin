package org.apptwin.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.apptwin.MainDestination
import org.apptwin.MainViewModel
import org.apptwin.permissions.ClonePermissionAction
import org.apptwin.ui.theme.AppTwinTheme

private data class DestinationItem(
    val destination: MainDestination,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    DestinationItem(MainDestination.HOME, "首頁", Icons.Default.Home),
    DestinationItem(MainDestination.SETTINGS, "設定", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTwinApp(
    viewModel: MainViewModel,
    onOpenStorageSettings: () -> Unit,
    onOpenPermissionSettings: (String) -> Unit,
    onShareDiagnostics: (String) -> Unit,
) {
    val state = viewModel.uiState
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateGroup by rememberSaveable { mutableStateOf(false) }
    var pendingPermissionAppKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPermissionName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSettingsPermission by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsPermissionsDeniedPermanently by rememberSaveable {
        mutableStateOf(emptySet<String>())
    }
    val context = LocalContext.current
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val appKey = pendingPermissionAppKey
        val permission = pendingPermissionName
        val app = state.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.launchKey == appKey }
        if (app != null && permission != null) {
            viewModel.setClonePermission(app, permission, granted)
        }
        pendingPermissionAppKey = null
        pendingPermissionName = null
    }
    val settingsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permission = pendingSettingsPermission
        if (
            !granted && permission != null &&
            context.findActivity()?.shouldShowRequestPermissionRationale(permission) == false
        ) {
            settingsPermissionsDeniedPermanently += permission
        }
        pendingSettingsPermission = null
        viewModel.refresh()
    }
    val onSetClonePermission: (org.apptwin.GroupAppItem, String, Boolean) -> Unit =
        { app, permission, granted ->
            if (!granted || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                viewModel.setClonePermission(app, permission, granted)
            } else {
                pendingPermissionAppKey = app.launchKey
                pendingPermissionName = permission
                permissionLauncher.launch(permission)
            }
        }
    val pickerGroup = state.appPickerGroupId?.let { selectedId ->
        state.groups.firstOrNull { it.groupId == selectedId }
    }
    BackHandler(
        enabled = state.pendingDeepLink != null || pickerGroup != null,
    ) {
        when {
            state.pendingDeepLink != null -> viewModel.closeDeepLink()
            pickerGroup != null -> viewModel.closeAppPicker()
            else -> Unit
        }
    }

    LaunchedEffect(state.messageId) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage(state.messageId)
    }

    LaunchedEffect(state.destination, state.isRefreshing) {
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(state.diagnosticsReportId) {
        val report = state.diagnosticsReport ?: return@LaunchedEffect
        onShareDiagnostics(report)
        viewModel.consumeDiagnostics(state.diagnosticsReportId)
    }

    AppTwinTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val useNavigationRail = maxWidth >= 720.dp
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useNavigationRail && pickerGroup == null) {
                        AppNavigationRail(
                            selected = state.destination,
                            onSelect = viewModel::navigate,
                        )
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        topBar = {
                            CenterAlignedTopAppBar(
                                navigationIcon = {
                                    if (pickerGroup != null) {
                                        IconButton(
                                            onClick = viewModel::closeAppPicker,
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "返回分身空間",
                                            )
                                        }
                                    }
                                },
                                title = {
                                    Text(
                                        when {
                                            pickerGroup != null -> "加入 App"
                                            state.destination == MainDestination.HOME -> "AppTwin"
                                            else -> "設定"
                                        },
                                    )
                                },
                            )
                        },
                        bottomBar = {
                            if (!useNavigationRail && pickerGroup == null) {
                                AppNavigationBar(
                                    selected = state.destination,
                                    onSelect = viewModel::navigate,
                                )
                            }
                        },
                        floatingActionButton = {
                            if (
                                state.destination == MainDestination.HOME &&
                                pickerGroup == null
                            ) {
                                ExtendedFloatingActionButton(
                                    onClick = { showCreateGroup = true },
                                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    text = { Text("建立空間") },
                                )
                            }
                        },
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            if (
                                state.busyPackageName != null ||
                                state.busyGroupId != null ||
                                state.launchingAppKey != null ||
                                state.uninstallingAppKey != null ||
                                state.clearingStorageGroupId != null
                            ) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            when {
                                pickerGroup != null -> AppPickerScreen(
                                    state = state,
                                    group = pickerGroup,
                                    onSelect = viewModel::selectApp,
                                )
                                state.destination == MainDestination.HOME -> HomeScreen(
                                    state = state,
                                    onCreateGroup = { showCreateGroup = true },
                                    onLaunch = viewModel::launchGroupApp,
                                    onAddApp = viewModel::openAppPicker,
                                    onRenameSpace = viewModel::renameGroup,
                                    onEnableGms = viewModel::enableGms,
                                    onDisableGms = viewModel::disableGms,
                                    onClearAllAppData = viewModel::clearAllGroupAppData,
                                    onUninstallApp = viewModel::uninstallGroupApp,
                                    onCreateShortcut = viewModel::createShortcut,
                                    onRepairApp = viewModel::repairClone,
                                    clearingStorageAppKey = state.clearingStorageAppKey,
                                    onClearStorage = viewModel::clearGroupAppStorage,
                                    onSetPermission = onSetClonePermission,
                                )
                                else -> SettingsScreen(
                                    state = state,
                                    onOpenStorageSettings = onOpenStorageSettings,
                                    onExportDiagnostics = viewModel::exportDiagnostics,
                                    notificationsGranted = notificationsGranted,
                                    onRequestNotifications = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            )
                                        }
                                    },
                                    onPermissionAction = { permission ->
                                        when (permission.action) {
                                            ClonePermissionAction.REQUEST_RUNTIME -> {
                                                if (
                                                    permission.permission in
                                                    settingsPermissionsDeniedPermanently
                                                ) {
                                                    onOpenPermissionSettings(permission.permission)
                                                } else {
                                                    pendingSettingsPermission = permission.permission
                                                    settingsPermissionLauncher.launch(
                                                        permission.permission,
                                                    )
                                                }
                                            }
                                            ClonePermissionAction.OPEN_APP_DETAILS,
                                            ClonePermissionAction.OPEN_SPECIAL_SETTINGS,
                                            -> onOpenPermissionSettings(permission.permission)
                                            ClonePermissionAction.NONE -> Unit
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (showCreateGroup) {
                CreateGroupDialog(
                    onDismiss = { showCreateGroup = false },
                    onConfirm = { name ->
                        viewModel.createGroup(name)
                        showCreateGroup = false
                    },
                )
            }
            if (state.showOnboarding) {
                OnboardingDialog(onContinue = viewModel::completeOnboarding)
            }
            state.pendingDeepLink?.takeUnless { state.showOnboarding }?.let { uri ->
                DeepLinkChooserDialog(
                    uri = uri,
                    candidates = state.deepLinkCandidates,
                    onDismiss = viewModel::closeDeepLink,
                    onSelect = viewModel::launchDeepLink,
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun DeepLinkChooserDialog(
    uri: String,
    candidates: List<org.apptwin.GroupAppItem>,
    onDismiss: () -> Unit,
    onSelect: (org.apptwin.GroupAppItem) -> Unit,
) {
    val origin = runCatching { Uri.parse(uri).host }.getOrNull().orEmpty()
    AlertDialog(
        modifier = Modifier.testTag("deep-link-chooser"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, contentDescription = null) },
        title = { Text("選擇開啟連結的分身") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    if (origin.isBlank()) "外部連結" else origin,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                candidates.forEach { item ->
                    FilledTonalButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        onClick = { onSelect(item) },
                    ) {
                        Text("${item.groupName} · ${item.appLabel}")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AppNavigationBar(
    selected: MainDestination,
    onSelect: (MainDestination) -> Unit,
) {
    NavigationBar {
        destinations.forEach { item ->
            NavigationBarItem(
                selected = selected == item.destination,
                onClick = { onSelect(item.destination) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    selected: MainDestination,
    onSelect: (MainDestination) -> Unit,
) {
    NavigationRail {
        destinations.forEach { item ->
            NavigationRailItem(
                selected = selected == item.destination,
                onClick = { onSelect(item.destination) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
internal fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val trimmedName = name.trim()
    val nameError = when {
        name.isNotEmpty() && trimmedName.isEmpty() -> "名稱不能只有空白"
        trimmedName.length > 40 -> "名稱最多 40 個字元"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建立分身空間") },
        text = {
            Column {
                Text(
                    "每個空間都有獨立的登入狀態與 App 資料，原始 App 和其他空間不受影響。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("空間名稱") },
                    placeholder = { Text("例如：工作、私人") },
                    isError = nameError != null,
                    supportingText = nameError?.let { message -> { Text(message) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmedName.isNotEmpty() && trimmedName.length <= 40,
                onClick = { onConfirm(trimmedName) },
            ) {
                Text("建立")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
