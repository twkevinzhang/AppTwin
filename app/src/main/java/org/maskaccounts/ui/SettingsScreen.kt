package org.maskaccounts.ui

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
import org.maskaccounts.MainUiState

@Composable
fun SettingsScreen(
    state: MainUiState,
    onOpenStorageSettings: () -> Unit,
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
                        "尚未開啟。M0 需要這項特殊權限瀏覽共享相片與 Download。"
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
                icon = { Icon(Icons.Default.Security, contentDescription = null) },
                title = "執行環境",
            ) {
                Text(
                    "目前已完成 LINE 與蝦皮的實機 clone 啟動路徑。其他 App 可以建立分身記錄，但啟動相容性仍需逐一驗證。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard(
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                title = "M0 多實例邊界",
            ) {
                Text(
                    "多張卡片目前共用 virtual user 0；它們是可管理的分身入口，尚不代表彼此已有獨立登入資料。",
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
