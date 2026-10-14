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
import androidx.lifecycle.repeatOnLifecycle
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
    onAddBackgroundServiceTile: () -> Unit = {},
) {
    val home by viewModel.homeState.collectWhileStarted()
    val picker by viewModel.pickerState.collectWhileStarted()
    val detailsState by viewModel.spaceDetailsState.collectWhileStarted()
    val settings by viewModel.settingsState.collectWhileStarted()
    val session by viewModel.sessionState.collectWhileStarted()
    val navigation by viewModel.navigationState.collectWhileStarted()
    val backgroundServiceState by viewModel.backgroundServiceState.collectWhileStarted()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(navigation.destination, navigation.selectedSpaceId, backgroundServiceState.enabled, backgroundServiceState.loaded, lifecycleOwner) {
        if (navigation.destination == MainDestination.SPACE_DETAILS && navigation.selectedSpaceId != null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                observeSpaceHealth(viewModel::refreshSpaceBackgroundHealth, viewModel::stopSpaceBackgroundObservation)
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateGroup by rememberSaveable { mutableStateOf(false) }
    var pendingClonePermissionTicket by rememberSaveable { mutableStateOf<Long?>(null) }
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
    ) { _ ->
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val ticket = pendingClonePermissionTicket
        pendingClonePermissionTicket = null
        viewModel.completeClonePermissionRequest(null, granted, ticket)
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ticket = pendingClonePermissionTicket
        pendingClonePermissionTicket = null
        val permission = viewModel.pendingClonePermissionName()
        if (ticket == null || ticket != viewModel.pendingClonePermissionTicket()) return@rememberLauncherForActivityResult
        if (permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
            viewModel.cancelClonePermissionRequest()
            if (grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true || grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                onOpenPermissionSettings(permission)
            }
        } else {
            viewModel.completeClonePermissionRequest(permission, grants[permission] == true, ticket)
        }
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
    val settingsLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> pendingSettingsPermission = null; viewModel.refresh() }
    val backupLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { location -> if (location != null) viewModel.setGlobalBackupLocation(location) }
    val createFromBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source != null) viewModel.createSpaceFromBackup(source)
    }
    val onSetClonePermission: (org.apptwin.GroupAppItem, String, Boolean) -> Unit =
        permissionAction@{ app, permission, granted ->
            // An old platform dialog must finish before another request can replace its ticket.
            if (pendingClonePermissionTicket != null) return@permissionAction
            if (!granted || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                viewModel.setClonePermission(app, permission, granted)
            } else if (permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    onOpenPermissionSettings(permission)
                } else {
                    viewModel.prepareClonePermissionRequest(app, permission) {
                        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                            viewModel.cancelClonePermissionRequest()
                            return@prepareClonePermissionRequest
                        }
                        pendingClonePermissionTicket = viewModel.pendingClonePermissionTicket()
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }
            } else {
                viewModel.prepareClonePermissionRequest(app, permission) {
                    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        viewModel.cancelClonePermissionRequest()
                        return@prepareClonePermissionRequest
                    }
                    pendingClonePermissionTicket = viewModel.pendingClonePermissionTicket()
                    if (permission == Manifest.permission.ACCESS_FINE_LOCATION || permission == Manifest.permission.ACCESS_COARSE_LOCATION) {
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    } else permissionLauncher.launch(permission)
                }
            }
        }
    val pickerGroup = picker.appPickerGroupId?.let { selectedId ->
        session.groups.firstOrNull { it.groupId == selectedId }
    }
    val detailsGroup = session.groups.firstOrNull { it.groupId == navigation.selectedSpaceId }
        .takeIf { navigation.destination == MainDestination.SPACE_DETAILS }
    BackHandler(
        enabled = navigation.pendingDeepLink != null || pickerGroup != null || detailsGroup != null,
    ) {
        when {
            navigation.pendingDeepLink != null -> viewModel.closeDeepLink()
            pickerGroup != null -> viewModel.closeAppPicker()
            detailsGroup != null -> viewModel.closeSpaceDetails()
            else -> Unit
        }
    }

    LaunchedEffect(navigation.messageId) {
        val message = navigation.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage(navigation.messageId)
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            kotlinx.coroutines.awaitCancellation()
        }
    }

    LaunchedEffect(settings.diagnosticsReportId) {
        val report = settings.diagnosticsReport ?: return@LaunchedEffect
        onShareDiagnostics(report)
        viewModel.consumeDiagnostics(settings.diagnosticsReportId)
    }

    AppTwinTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val useNavigationRail = maxWidth >= 720.dp
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useNavigationRail && pickerGroup == null && detailsGroup == null) {
                        AppNavigationRail(
                            selected = navigation.destination,
                            onSelect = viewModel::navigate,
                        )
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        topBar = {
                            CenterAlignedTopAppBar(
                                navigationIcon = {
                                    if (pickerGroup != null || detailsGroup != null) {
                                        IconButton(
                                            onClick = { if (pickerGroup != null) viewModel.closeAppPicker() else viewModel.closeSpaceDetails() },
                                            modifier = Modifier.testTag("space-details-back"),
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
                                            detailsGroup != null -> "空間詳細資訊"
                                            navigation.destination == MainDestination.HOME -> "AppTwin"
                                            else -> "設定"
                                        },
                                    )
                                },
                            )
                        },
                        bottomBar = {
                            if (!useNavigationRail && pickerGroup == null && detailsGroup == null) {
                                AppNavigationBar(
                                    selected = navigation.destination,
                                    onSelect = viewModel::navigate,
                                )
                            }
                        },
                        floatingActionButton = {
                            if (
                                navigation.destination == MainDestination.HOME &&
                                pickerGroup == null &&
                                (home.groups.isNotEmpty() ||
                                    (!home.isRefreshing && home.error == null && home.dataWarnings.isEmpty()))
                            ) {
                                ExtendedFloatingActionButton(
                                    onClick = { if (!session.isCreatingGroup) showCreateGroup = true },
                                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    text = { Text(if (session.isCreatingGroup) "建立中…" else "建立空間") },
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
                                session.isCreatingGroup || session.isSpaceActionBusy ||
                                session.busyPackageName != null ||
                                session.busyGroupId != null ||
                                session.launchingAppKey != null ||
                                session.uninstallingAppKey != null ||
                                session.clearingStorageGroupId != null ||
                                session.archiveBusyGroupId != null ||
                                session.isCreatingSpaceFromBackup
                            ) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            when {
                                pickerGroup != null -> AppPickerScreen(
                                    state = picker,
                                    session = session,
                                    group = pickerGroup,
                                    onSelect = viewModel::selectApp,
                                )
                                detailsGroup != null -> {
                                    SpaceDetailsScreen(
                                        state = detailsState,
                                        session = session,
                                        group = detailsGroup,
                                        backgroundServiceState = backgroundServiceState,
                                        onRetryBackground = viewModel::retrySpaceBackground,
                                        globalBackupLocation = settings.globalBackupLocation,
                                        onRefresh = viewModel::refreshSpaceDetails,
                                        onSetPermission = { app, permission, granted ->
                                            onSetClonePermission(app, permission, granted)
                                        },
                                        onAddAccount = viewModel::addSpaceAccount,
                                        onRemoveAccount = { viewModel.removeSpaceAccount(org.apptwin.SpaceAccountEntry(it.name, it.type)) },
                                        onSynchronizeAccount = { account, authority -> viewModel.synchronizeSpaceAccount(org.apptwin.SpaceAccountEntry(account.name, account.type, account.syncAuthorities), authority) },
                                        onCreateFromBackup = { viewModel.createSpaceFromBackup(Uri.parse(it.uri)) },
                                        onDeleteBackup = { viewModel.deleteBackup(it.id) },
                                        onExport = { viewModel.exportToGlobalLocation(detailsGroup.groupId) },
                                        onOpenGlobalSettings = { viewModel.navigate(MainDestination.SETTINGS) },
                                        onRename = { viewModel.renameGroup(detailsGroup.groupId, it) },
                                        onClearAllAppData = { viewModel.clearAllGroupAppData(detailsGroup.groupId) },
                                        onDeleteSpace = { viewModel.deleteGroup(detailsGroup.groupId); viewModel.closeSpaceDetails() },
                                    )
                                }
                                navigation.destination == MainDestination.HOME -> HomeScreen(
                                    state = home,
                                    session = session,
                                    onRefresh = viewModel::refresh,
                                    onCreateGroup = { showCreateGroup = true },
                                    onLaunch = viewModel::launchGroupApp,
                                    onAddApp = viewModel::openAppPicker,
                                    onRenameSpace = viewModel::renameGroup,
                                    onDeleteSpace = viewModel::deleteGroup,
                                    onOpenSpaceDetails = viewModel::openSpaceDetails,
                                    onClearAllAppData = viewModel::clearAllGroupAppData,
                                    onUninstallApp = viewModel::uninstallGroupApp,
                                    onCreateShortcut = viewModel::createShortcut,
                                    onRepairApp = viewModel::repairClone,
                                    clearingStorageAppKey = session.clearingStorageAppKey,
                                    onClearStorage = viewModel::clearGroupAppStorage,
                                    onSetPermission = onSetClonePermission,
                                )
                                else -> SettingsScreen(
                                    state = settings,
                                    session = session,
                                    backgroundServiceState = backgroundServiceState,
                                    onBackgroundServiceChange = { viewModel.setBackgroundServiceEnabled(it) },
                                    onBackgroundServiceRetry = {
                                        backgroundServiceState.retry(
                                            requestChange = { viewModel.setBackgroundServiceEnabled(it) },
                                            refresh = { viewModel.refreshBackgroundService() },
                                        )
                                    },
                                    onAddBackgroundServiceTile = onAddBackgroundServiceTile,
                                    onArchiveCompressionChange = viewModel::setArchiveCompression,
                                    onOpenStorageSettings = onOpenStorageSettings,
                                    onExportDiagnostics = viewModel::exportDiagnostics,
                                    globalBackupLocation = settings.globalBackupLocation,
                                    onChooseGlobalBackupLocation = { backupLocationLauncher.launch(null) },
                                    onCreateSpaceFromBackup = {
                                        createFromBackupLauncher.launch(
                                            arrayOf(
                                                "application/zip",
                                                "application/octet-stream",
                                            ),
                                        )
                                    },
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
                                                    if (permission.permission == Manifest.permission.ACCESS_FINE_LOCATION || permission.permission == Manifest.permission.ACCESS_COARSE_LOCATION) {
                                                        settingsLocationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                                                    } else settingsPermissionLauncher.launch(permission.permission)
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
            home.createSpaceError?.let { error ->
                AlertDialog(
                    modifier = Modifier.testTag("create-space-error-dialog"),
                    onDismissRequest = viewModel::dismissCreateSpaceError,
                    title = { Text("建立空間失敗") },
                    text = { Text(error) },
                    confirmButton = { TextButton(onClick = viewModel::dismissCreateSpaceError, modifier = Modifier.testTag("dismiss-create-space-error")) { Text("確定") } },
                )
            }
            if (navigation.showOnboarding) {
                OnboardingDialog(onContinue = viewModel::completeOnboarding)
            }
            navigation.pendingDeepLink?.takeUnless { navigation.showOnboarding }?.let { uri ->
                DeepLinkChooserDialog(
                    uri = uri,
                    candidates = navigation.deepLinkCandidates,
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
        modifier = Modifier.testTag("create-space-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("建立分身空間") },
        text = {
            Column {
                Text(
                    "每個空間都有獨立的登入狀態、App 資料與 Google 服務。Google 服務由 microG 提供，會連線至 Google 的裝置註冊、帳號及推播端點；只有初始化完成才會建立空間。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .testTag("create-space-name"),
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
                modifier = Modifier.testTag("confirm-create-space"),
                enabled = trimmedName.isNotEmpty() && trimmedName.length <= 40,
                onClick = { onConfirm(trimmedName) },
            ) {
                Text("建立")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
