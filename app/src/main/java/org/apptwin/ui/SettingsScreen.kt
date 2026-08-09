package org.apptwin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.apptwin.MainUiState

@Composable
fun SettingsScreen(
    state: MainUiState,
    onOpenStorageSettings: () -> Unit,
    onExportDiagnostics: () -> Unit,
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsCard(
                icon = {
                    Icon(
                        if (notificationsGranted) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                    )
                },
                title = "分身通知",
            ) {
                Text(
                    if (notificationsGranted) {
                        "通知權限已開啟；轉送通知會標示來源分身空間。"
                    } else {
                        "尚未允許通知。Android 13 以上必須先允許，分身通知才會顯示。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!notificationsGranted) {
                    FilledTonalButton(
                        modifier = Modifier.padding(top = 16.dp),
                        onClick = onRequestNotifications,
                    ) {
                        Text("允許通知")
                    }
                }
            }
        }
        item {
            SettingsCard(
                icon = {
                    Icon(
                        if (state.allFilesGranted) Icons.Default.CheckCircle else Icons.Default.Folder,
                        contentDescription = null,
                    )
                },
                title = "共享檔案存取",
            ) {
                Text(
                    if (state.allFilesGranted) {
                        "已開啟 · Download ${state.downloadCount} 項 · 相片目錄 ${state.photoCount} 項"
                    } else {
                        "尚未開啟。只有需要瀏覽共享相片或 Download 時才前往系統授權。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = onOpenStorageSettings,
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text(
                        if (state.allFilesGranted) "檢視系統設定" else "開啟權限設定",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        item {
            SettingsCard(
                icon = { Icon(Icons.Default.Share, contentDescription = null) },
                title = "診斷與支援",
            ) {
                Text(
                    "報告只包含版本、狀態與穩定錯誤代碼；空間與 package 會以每次不同的代碼取代，不包含帳號、通知、Intent、檔案內容或路徑。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = onExportDiagnostics,
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("產生並分享報告", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            SettingsCard(
                icon = { Icon(Icons.Default.Security, contentDescription = null) },
                title = "執行環境",
            ) {
                Text(
                    "僅特定 LINE／蝦皮版本曾在 Android 12 實機啟動；版本或裝置不同時會重新標示為未驗證。其他 App 可嘗試加入，但登入、通知或系統功能可能不相容。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard(
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                title = "分身空間的隔離範圍",
            ) {
                Text(
                    "同一空間內的 App 使用同一套身分環境；不同空間的 App 資料、登入狀態、安裝狀態與 App 所見權限決策彼此隔離。相機／麥克風硬體能力仍可能因 App 與裝置而異。刪除空間時，其中的登入與資料也會永久刪除。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    icon: @Composable () -> Unit,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                icon()
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.padding(top = 14.dp), content = { content() })
        }
    }
}
