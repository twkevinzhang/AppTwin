package org.maskaccounts.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import org.maskaccounts.CreateDraft
import org.maskaccounts.MainDestination
import org.maskaccounts.MainUiState
import org.maskaccounts.MainViewModel
import org.maskaccounts.ui.theme.MaskAccountsTheme

private data class DestinationItem(
    val destination: MainDestination,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    DestinationItem(MainDestination.INSTANCES, "分身", Icons.Default.Home),
    DestinationItem(MainDestination.APPS, "App", Icons.Default.Apps),
    DestinationItem(MainDestination.SETTINGS, "設定", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaskAccountsApp(
    viewModel: MainViewModel,
    onOpenStorageSettings: () -> Unit,
) {
    val state = viewModel.uiState
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.messageId) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage(state.messageId)
    }

    MaskAccountsTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val useNavigationRail = maxWidth >= 720.dp
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useNavigationRail) {
                        AppNavigationRail(
                            selected = state.destination,
                            onSelect = viewModel::navigate,
                        )
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Text(
                                        when (state.destination) {
                                            MainDestination.INSTANCES -> "我的分身"
                                            MainDestination.APPS -> "選擇 App"
                                            MainDestination.SETTINGS -> "設定"
                                        },
                                    )
                                },
                            )
                        },
                        bottomBar = {
                            if (!useNavigationRail) {
                                AppNavigationBar(
                                    selected = state.destination,
                                    onSelect = viewModel::navigate,
                                )
                            }
                        },
                        floatingActionButton = {
                            if (state.destination == MainDestination.INSTANCES) {
                                ExtendedFloatingActionButton(
                                    onClick = { viewModel.navigate(MainDestination.APPS) },
                                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    text = { Text("新增分身") },
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
                            if (state.busyPackageName != null || state.launchingInstanceId != null) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            when (state.destination) {
                                MainDestination.INSTANCES -> InstancesScreen(
                                    state = state,
                                    onLaunch = viewModel::launchInstance,
                                    onRename = viewModel::renameInstance,
                                    onDelete = viewModel::deleteInstance,
                                    onAdd = { viewModel.navigate(MainDestination.APPS) },
                                )
                                MainDestination.APPS -> AppPickerScreen(
                                    state = state,
                                    onSelect = viewModel::selectApp,
                                )
                                MainDestination.SETTINGS -> SettingsScreen(
                                    state = state,
                                    onOpenStorageSettings = onOpenStorageSettings,
                                )
                            }
                        }
                    }
                }
            }
            state.createDraft?.let { draft ->
                CreateInstanceDialog(
                    draft = draft,
                    onDismiss = viewModel::dismissCreateDraft,
                    onConfirm = viewModel::createInstance,
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
private fun CreateInstanceDialog(
    draft: CreateDraft,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var displayName by remember(draft.app.packageName, draft.suggestedName) {
        mutableStateOf(draft.suggestedName)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建立 ${draft.app.label} 分身") },
        text = {
            Column {
                Text(
                    "這個名稱只會顯示在 MaskAccounts 裡。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                    label = { Text("分身名稱") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = displayName.isNotBlank(),
                onClick = { onConfirm(displayName) },
            ) {
                Text("建立")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
