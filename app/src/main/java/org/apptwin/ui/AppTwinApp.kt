package org.apptwin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.apptwin.MainDestination
import org.apptwin.MainViewModel
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
) {
    val state = viewModel.uiState
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateGroup by remember { mutableStateOf(false) }
    val pickerGroup = state.appPickerGroupId?.let { selectedId ->
        state.groups.firstOrNull { it.groupId == selectedId }
    }

    BackHandler(enabled = pickerGroup != null) {
        viewModel.closeAppPicker()
    }

    LaunchedEffect(state.messageId) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage(state.messageId)
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
                                        IconButton(onClick = viewModel::closeAppPicker) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "返回首頁",
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
                            if (state.destination == MainDestination.HOME && pickerGroup == null) {
                                ExtendedFloatingActionButton(
                                    onClick = { showCreateGroup = true },
                                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    text = { Text("新增群組") },
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
                                state.launchingPlayStoreGroupId != null ||
                                state.uninstallingAppKey != null
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
                                    onLaunch = viewModel::launchGroupApp,
                                    onLaunchPlayStore = viewModel::launchPlayStore,
                                    onAddApp = viewModel::openAppPicker,
                                    onPrepareGroup = viewModel::prepareGroup,
                                    onRenameGroup = viewModel::renameGroup,
                                    onDeleteGroup = viewModel::deleteGroup,
                                    onUninstallApp = viewModel::uninstallGroupApp,
                                    onCreateGroup = { showCreateGroup = true },
                                )
                                else -> SettingsScreen(
                                    state = state,
                                    onOpenStorageSettings = onOpenStorageSettings,
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
        }
    }
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
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增群組") },
        text = {
            Column {
                Text(
                    "每個群組會立即建立專屬隔離環境；Google 服務則在加入 App 後按需準備。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("群組名稱") },
                    placeholder = { Text("例如：工作、私人") },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) {
                Text("建立")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
